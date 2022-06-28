package ag.dc.minimalzio.users.messages.commands

import ag.dc.minimalzio.users.model.CreationUser
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

case class CreateUser[M <: Serializable](
  user: CreationUser,
  targetLocator: (Method, URL),
  headers: zhttp.http.Headers, 
  correlationId: UUID,
  issuer: String,
  messageId: UUID = UUID.randomUUID(), 
  createdAt: Instant = Instant.now(),
  metadata: Map[String, M] = Map()
) extends Command[CreationUser, M] {
  val headerData: Some[zhttp.http.Headers] = Some(headers)
  val messageData = user
}
object CreateUser:
  def fromRequestPartialZIO: PartialFunction[Request, ZIO[Any, Throwable, Either[RequestError, Command[?, ?]]]] = 
    case req@(Method.POST -> !! / "users") => {
      req.bodyAsString.flatMap(bodyString => 
        val jsonErrOrCreateUser = 
          CreationUser.given_JsonDecoder_CreationUser.decodeJson(bodyString)
        ZIO.succeed(
          jsonErrOrCreateUser match {
            case Left(err) => Left(RequestError(Status.BadRequest, s"Invalid Json: $err"))
            case Right(user) => Right(
              CreateUser(
                user = user,
                targetLocator = (req.method, req.url),
                headers = req.headers,
                correlationId = 
                  req.headerValue(CommonHeaders.CorrelationId.getName)
                  .flatMap( str => Try(UUID.fromString(str)).toOption)
                  .getOrElse(UUID.randomUUID()),
                issuer = req.userAgent.map(_.toString).getOrElse("Browser")
              )
            )
          }
        )   
      )
    }
  
