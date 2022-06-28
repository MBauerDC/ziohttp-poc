package ag.dc.minimalzio.users.messages.commands

import ag.dc.minimalzio.users.model.ModificationUser
import java.util.UUID
import java.time.Instant
import ag.dc.minimalzio.cqrs.CQRS.Command
import zhttp.http.Request
import zio.ZIO
import zhttp.http.Path
import zhttp.http.*
import ag.dc.minimalzio.utils.Utils.RequestError
import scala.util.Try
import ag.dc.minimalzio.utils.Utils.CommonHeaders

case class ChangeUserDataById[M <: Serializable](
  id: UUID,
  user: ModificationUser,
  targetLocator: (Method, URL),
  headers: zhttp.http.Headers, 
  correlationId: UUID,
  issuer: String,
  messageId: UUID = UUID.randomUUID(), 
  createdAt: Instant = Instant.now(),
  metadata: Map[String, M] = Map()
) extends Command[ModificationUser, M] {
  val headerData: Some[zhttp.http.Headers] = Some(headers)
  val messageData = user
}
object ChangeUserDataById {
  def fromRequestPartialZIO: PartialFunction[Request, ZIO[Any, Throwable, Either[RequestError, Command[?, ?]]]] = {
    case req@(Method.PATCH -> !! / "users" / userId) if (Try(UUID.fromString(userId)).isSuccess) => {
      req.bodyAsString.flatMap(bodyString => 
        val errOrModificationUser = 
          ModificationUser.given_JsonDecoder_ModificationUser.decodeJson(bodyString)
        ZIO.succeed(  
          errOrModificationUser match {
            case Left(err) => Left(RequestError(Status.BadRequest, s"Invalid JSON: $err"))
            case Right(user) => 
              Right(
                ChangeUserDataById(
                  id = UUID.fromString(userId),
                  user = user,
                  targetLocator = (req.method, req.url),
                  headers = req.headers, 
                  correlationId = 
                    req.headerValue(CommonHeaders.CorrelationId.getName)
                    .flatMap( str => Try(UUID.fromString(str)).toOption)
                    .getOrElse(UUID.randomUUID()),
                  issuer = req.userAgent.map(_.toString).getOrElse("Browser"),
                )
              )
          }
        )
      )
    }
  }
}