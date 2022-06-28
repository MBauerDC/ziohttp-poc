package ag.dc.minimalzio.utils

import ag.dc.minimalzio.cqrs.CQRS.IncomingHTTPMessageFactory
import ag.dc.minimalzio.cqrs.CQRS.GenericIncomingHTTPMessageFactory
import ag.dc.minimalzio.cqrs.CQRS.CommandFactory
import ag.dc.minimalzio.cqrs.CQRS.QueryFactory
import ag.dc.minimalzio.cqrs.CQRS.GenericCommandFactory
import ag.dc.minimalzio.cqrs.CQRS.GenericQueryFactory
import ag.dc.minimalzio.cqrs.CQRS.IncomingHTTPMessageDispatcher
import ag.dc.minimalzio.cqrs.CQRS.GenericIncomingHTTPMessageDispatcher
import zhttp.http.Response
import ag.dc.minimalzio.cqrs.CQRS.QueryHandler
import ag.dc.minimalzio.cqrs.CQRS.CommandHandler
import ag.dc.minimalzio.utils.Utils.RequestError
import ag.dc.minimalzio.users.*
import zio.UIO
import zhttp.http.Request
import ag.dc.minimalzio.cqrs.CQRS.Query
import ag.dc.minimalzio.cqrs.CQRS.Command
import zio.ZIO
import zio.UIO
import zio.URIO
import zio.Ref
import java.util.UUID
import ag.dc.minimalzio.users.model.User
import ag.dc.minimalzio.cqrs.CQRS.IncomingHTTPMessage
import zhttp.http.Http


object Bootstrap {

  val commandFactory: UIO[CommandFactory] = 
    ag.dc.minimalzio.users.Bootstrap.commandConstructors.map(cctors => 
        new GenericCommandFactory(cctors)
    )

  val queryFactory: UIO[QueryFactory] = 
    ag.dc.minimalzio.users.Bootstrap.queryConstructors.map(qctors => 
        new GenericQueryFactory(qctors)
    )

  val incomingHTTPMessageFactory: UIO[IncomingHTTPMessageFactory] = 
    (queryFactory <&> commandFactory).map(t => 
      val qf = t._1
      val cf = t._2
      new GenericIncomingHTTPMessageFactory(qf, cf)
    )

  val queryHandlers: ZIO[Ref[Map[UUID, User]], Throwable, List[QueryHandler[?, Response]]] = 
    ag.dc.minimalzio.users.Bootstrap.userQueryHandler.map(List(_))

  val commandHandlers: ZIO[Ref[Map[UUID, User]], Throwable, List[CommandHandler[?, Response]]] = 
    ag.dc.minimalzio.users.Bootstrap.userCommandHandler.map(List(_))

  val incomingHTTPMessageDispatcher: ZIO[Ref[Map[UUID, User]], Throwable, IncomingHTTPMessageDispatcher[Response]] = 
    (queryHandlers <&> commandHandlers).map(t =>
      val qhs = t._1    
      val chs = t._2
      new GenericIncomingHTTPMessageDispatcher(
        qhs,
        chs
      )
    )

  def getBootstrappedApp = 
    (incomingHTTPMessageFactory <&> incomingHTTPMessageDispatcher).map(t =>
      val mf = t._1
      val md = t._2
      Http.fromFunctionZIO[Request] {
        mf.requestToMessage.andThen(_.flatMap(e =>
          (e match {
            case Left(err) => ZIO.succeed(err.toResponse())
            case Right(msg) => md.dispatchZIO(msg.asInstanceOf[Query[?, ?] | Command[?, ?]])
          })
        ))
      }    
    )
}