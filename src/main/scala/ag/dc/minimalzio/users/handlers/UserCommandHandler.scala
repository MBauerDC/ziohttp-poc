package ag.dc.minimalzio.users.handlers

import ag.dc.minimalzio.cqrs.CQRS.CommandHandler
import ag.dc.minimalzio.users.messages.commands.CreateUser
import ag.dc.minimalzio.users.messages.commands.ChangeUserDataById
import ag.dc.minimalzio.users.model.User
import zio.Ref
import zhttp.http.*
import java.util.UUID
import zio.ZIO
import java.time.Instant

class UserCommandHandler(map: Ref[Map[UUID, User]]) extends CommandHandler[(CreateUser[?] | ChangeUserDataById[?]), Response] {
    def handleCommandZIO(command: CreateUser[?] | ChangeUserDataById[?]): ZIO[Any, Throwable, Response] = 
      command match {
        case create:CreateUser[?] => handleCreate(create)
        case update:ChangeUserDataById[?] => handleUpdate(update)
      }

    def handleCreate(command: CreateUser[?]): ZIO[Any, Throwable, Response] = 
      val u = command.user
      val now = Instant.now
      for {
        map <- this.map.get
        id = command.messageData.id.getOrElse(UUID.randomUUID())
        user = User(id, u.name, u.email, now, "Some User", now, "Some User")
        _ <- this.map.set(map + ((id, user)))
      } yield Response.text(id.toString()).setStatus(Status.Created)
    
    def handleUpdate(command: ChangeUserDataById[?]): ZIO[Any, Throwable, Response] = 
      val u = command.user
      val now = Instant.now
      val id = command.id
      val zioMapAndUser = for {
        map <- this.map.get
        existingUserOpt = map.get(id)
      } yield (map, existingUserOpt)

      val zioMapAndNewUser = zioMapAndUser.map(
        (map, userOpt) => 
          for {
            existingUser <- userOpt
            name = u.name.getOrElse(existingUser.name)
            email = u.email.getOrElse(existingUser.email)
            user = existingUser.copy(name = name, email = email, lastModifiedAt = now, lastModifiedByUserName = "Some User")
          } yield (map, user)
      )
      zioMapAndNewUser.flatMap(
        opt => 
          opt match {
            case None => ZIO.succeed(Response.text(s"User with id $id is not known."))
            case Some(map, newUser) =>              
              for {
                _ <- this.map.set((map - id) + ((id, newUser)))
                sucess = Response.status(Status.Ok)
              } yield sucess
          }
      )
  }