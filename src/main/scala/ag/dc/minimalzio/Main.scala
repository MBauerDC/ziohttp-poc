package ag.dc.minimalzio

import zio._
import zhttp.http._
import zhttp.service.Server
import zhttp.service.server.ServerChannelFactory
import io.netty.channel.EventLoopGroup
import zhttp.service.Server.Start
import io.netty.handler.codec.CharSequenceValueConverter
import java.util.UUID
import scala.util.Try
import java.io.IOException

import ag.dc.minimalzio.middleware.*
import ag.dc.minimalzio.middleware.DCMiddleware

object Main extends ZIOAppDefault  {

  type Port = Int

  // Implicitly allow use of Ints as Ports
  given Conversion[Int, Port] with
    def apply(i: Int): Port = i
  

  val DEFAULT_PORT: Port = 8090
  val DEFAULT_N_THREADS: Int = 1

  private def getPort(args: Chunk[String]) = 
    args.headOption.flatMap[Port](_.toIntOption).getOrElse(DEFAULT_PORT)
  
  private def getNThreads(args: Chunk[String]) = 
    args.drop(1).headOption.flatMap[Int](_.toIntOption).getOrElse(DEFAULT_N_THREADS)
  
  private def getMiddlewares = 
    Middleware.debug ++
    Middleware.addHeader("X-Environment", "Dev")

  val a: PartialFunction[Request, Any] = {
    case req @ Method.POST -> !! / "fruits" / "a" =>
    Response.text(req.bodyAsString.getOrElse("No body!"))
  }

  val method: PartialFunction[Request, Response] = {
      case Method.GET -> !! / "text" / name => Response.text("Hello World!")
    }  

  private def getApp = 
    Http.collect[Request] {
      case Method.GET -> !! / "text" / name => Response.text("Hello World!")
    }

  private def logStart(start: Start, nThreads: Int) =
    Console.printLine(
      s"Server started at port ${start.port} with ${nThreads} EventLoopGroup threads."
    )
  
  private def attachMiddleware[R1 >: ZEnv, R2 >: ZEnv, E1 <: Throwable | Nothing, E2 <: Throwable | Nothing] (
    app: Http[R1, E1 & E2, Request, Response], 
    mw: List[Middleware[R1 & R2, E1 & E2, Request, Response, Request, Response]]
  ): HttpApp[R1 & R2, E1 & E2] = {
    val folded: Middleware[R1 & R2, E1 & E2, Request, Response, Request, Response] = 
      mw.fold(Middleware.identity)((a,b) => a >>> b)
    app.@@[R1 & R2, E1 & E2, Request, Response, Request, Response](folded)
  }
  
  private def composeServer[R1 >: ZEnv, R2 >: ZEnv, E1 <: Throwable, E2 <: Throwable](
    port: Port, 
    app: Http[R1, E1, Request, Response], 
    mw: List[Middleware[R2, E1 & E2, Request, Response, Request, Response]]
  ): ZIO[Any, Nothing, Server[R1 & R2, E1]] = 
    ZIO.succeed( 
      Server.port(port) ++              // Setup port
      Server.paranoidLeakDetection ++ // Paranoid leak detection (affects performance)
      Server.app(attachMiddleware(app, mw)) 
    )

  val run = getArgs.flatMap { args =>
    val port: Port = getPort(args)
    val nThreads: Int = getNThreads(args)
    val app = getApp
    val systemLayer: ZLayer[Any, Nothing, Clock & Random & System & Console] = 
      Clock.live ++ Random.live ++ System.live ++ Console.live

    ZIO.scoped {
      for {
        mwRef  <- DCMiddleware.bootstrapMiddlewares
        mw     <- mwRef.get
        server <- composeServer(port, app, mw)
        start  <- server.make
        _      <- logStart(start, nThreads) *> ZIO.never
      } yield ()
    }.provide(systemLayer, ServerChannelFactory.auto, zhttp.service.EventLoopGroup.auto(nThreads))
  }
}
