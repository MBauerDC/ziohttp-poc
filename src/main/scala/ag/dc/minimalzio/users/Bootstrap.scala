package ag.dc.minimalzio.users

import ag.dc.minimalzio.utils.Utils.RequestError
import ag.dc.minimalzio.cqrs.CQRS.CommandHandler
import ag.dc.minimalzio.cqrs.CQRS.QueryHandler
import ag.dc.minimalzio.users.model.User
import ag.dc.minimalzio.users.messages.commands.ChangeUserDataById
import ag.dc.minimalzio.users.messages.commands.CreateUser
import ag.dc.minimalzio.users.messages.queries.GetAllUsers
import ag.dc.minimalzio.users.messages.queries.GetUserById
import ag.dc.minimalzio.users.handlers.UserCommandHandler
import ag.dc.minimalzio.users.handlers.UserQueryHandler
import zhttp.http.Response
import zio.Ref
import zio.ZIO
import zio.UIO
import zio.URIO
import java.util.UUID
import ag.dc.minimalzio.users.handlers.UserQueryHandler
import zhttp.http.Request
import ag.dc.minimalzio.cqrs.CQRS.Command
import ag.dc.minimalzio.cqrs.CQRS.Query

object Bootstrap {
  val userMapRef: UIO[Ref[Map[UUID, User]]] = Ref.make(Map.empty)
  val userCommandHandler: URIO[Ref[Map[UUID, User]], UserCommandHandler]  = 
    ZIO.environment[Ref[Map[UUID, User]]].flatMap { mapRef =>
        ZIO.succeed(new UserCommandHandler(mapRef.get))
    }
  val userQueryHandler: URIO[Ref[Map[UUID, User]], UserQueryHandler]  = 
    ZIO.environment[Ref[Map[UUID, User]]].flatMap { mapRef =>
        ZIO.succeed(new UserQueryHandler(mapRef.get))
    }

  val commandConstructors: UIO[List[PartialFunction[Request, ZIO[Any, Throwable, Either[RequestError, Command[?, ?]]]]]] = 
    ZIO.succeed(List(ChangeUserDataById.fromRequestPartialZIO, CreateUser.fromRequestPartialZIO))

  val queryConstructors: UIO[List[PartialFunction[Request, ZIO[Any, Throwable, Either[RequestError, Query[?, ?]]]]]] = 
    ZIO.succeed(List(GetAllUsers.fromRequestPartialZIO, GetUserById.fromRequestPartialZIO))
}