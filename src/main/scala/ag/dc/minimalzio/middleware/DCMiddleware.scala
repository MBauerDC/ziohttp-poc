package ag.dc.minimalzio.middleware

import zhttp.http.*
import zhttp.http.middleware.*
import zio.*
import scala.util.Try
import java.util.UUID
import java.io.IOException

type BaseMiddleware[-R, +E] = HttpMiddleware[R, E]
type GeneralMiddleware = BaseMiddleware[Nothing, Throwable]

object DCMiddleware {

  def createMiddlewaresRef[R >: ZEnv, E <: Throwable | Nothing]: UIO[Ref[List[BaseMiddleware[R, E]]]] = 
    Ref.make(List(Middleware.identity))

  def ensureCorrelationId[R >: ZEnv]: HttpMiddleware[R, Nothing] = 
    val headerName = "X-Correlation-Id"
    Middleware.whenHeader(
      h =>  h.hasHeader(headerName) && 
            h.headerValue(headerName).map(
              v => Try(UUID.fromString(v)).isSuccess
            ).isDefined,
      Middleware.updateHeaders(
        h => h.addHeader(headerName, UUID.randomUUID.toString)
      )
    )

  def appendMiddlewares[R1 >: ZEnv, R2 >: ZEnv, E1 <: Throwable | Nothing, E2 <: Throwable | Nothing](
    mwRef: Ref[List[BaseMiddleware[R1 & R2, E1 & E2]]], 
    mws: List[BaseMiddleware[R1, E1 & E2]]
  ) =
    for {
      oldVal <- mwRef.get
      newVal = oldVal.appendedAll(mws)
      _ <- mwRef.set(newVal)
    } yield mwRef

  def bootstrapMiddlewares = 
    for {
      ref <- this.createMiddlewaresRef
      mws <- this.appendMiddlewares(
              ref,
              List(
                Middleware.debug,
                this.ensureCorrelationId
              )
            )
    } yield mws
}
