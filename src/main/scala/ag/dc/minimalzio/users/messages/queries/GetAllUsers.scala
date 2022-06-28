package ag.dc.minimalzio.users.messages.queries

import java.util.UUID
import java.time.Instant
import ag.dc.minimalzio.cqrs.CQRS.Query
import zhttp.http.Request
import zio.ZIO
import zhttp.http.Path
import zhttp.http.*
import ag.dc.minimalzio.utils.Utils.RequestError
import scala.util.Try
import ag.dc.minimalzio.utils.Utils.CommonHeaders
import zio.json.uuid.UUIDParser

case class GetAllUsers[M <: Serializable](
  targetLocator: (Method, URL),
  headers: zhttp.http.Headers, 
  correlationId: UUID,
  issuer: String,
  messageId: UUID = UUID.randomUUID(), 
  createdAt: Instant = Instant.now(),
  metadata: Map[String, M] = Map()
) extends Query[Serializable, M] {
  val headerData: Some[zhttp.http.Headers] = Some(headers)
  val messageData = None
}
object GetAllUsers:
  def fromRequestPartialZIO: PartialFunction[Request, ZIO[Any, Throwable, Either[RequestError, Query[?, ?]]]] = 
    case req@(Method.GET -> !! / "users") => {
      req.bodyAsString.flatMap(bodyString => 
        ZIO.succeed(
          Right(
            GetAllUsers(
              targetLocator = (req.method, req.url),
              headers = req.headers,
              correlationId = 
                req.headerValue(CommonHeaders.CorrelationId.getName)
                .flatMap( str => Try(UUID.fromString(str)).toOption)
                .getOrElse(UUID.randomUUID()),
              issuer = req.userAgent.map(_.toString).getOrElse("Browser")
              )
          )           
        )
      )
    }
  
