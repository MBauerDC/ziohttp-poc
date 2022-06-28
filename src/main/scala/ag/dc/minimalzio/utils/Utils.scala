package ag.dc.minimalzio.utils

import java.time.Instant
import zhttp.http.Request
import zhttp.http.Status
import java.nio.charset.Charset
import zhttp.http.Response
import zhttp.http.Headers
import zhttp.http.HttpData

object Utils {

  object Metadata {
    trait CreationTime {
      val createdAt: Instant
    }
  
    trait ModificationTime {
      val lastModifiedAt: Instant
    }
  
    trait CreationUserName {
      val createdByUserName: String
    }
  
    trait ModificationUserName {
      val lastModifiedByUserName: String
    }
  }

  case class RequestError(status: Status, message: String) extends Throwable {
    def toResponse(cs: Charset = Charset.defaultCharset()): Response = 
      Response(status, Headers(), HttpData.fromString(message, cs))
  }
  object RequestError {
    def apply(status: Status, message: String): RequestError =
      new RequestError(status, message)
  }

  enum CommonHeaders(name: String):
    def getName = this.name
    case CorrelationId extends CommonHeaders("X-Correlation-Id")
    case SessionId extends CommonHeaders("X-Session-Id")

  object HTTPRequest {
    extension (r: Request) def getHeaderValue[O]
      (name: String)
      (req: Request)
      (converter: Option[String => O] = None): Option[O|String] = 
      
      val valueOpt = req.headerValue(name)
      if converter == None then 
        valueOpt
      else
        valueOpt.map(converter.get)

    extension (r: Request) def getHeaderValues[O]
      (name: String)
      (req: Request)
      (converter: Option[String => O] = None): List[O|String] = 
      
      val valueList = req.headerValues(name)
      if converter == None then 
        valueList
      else
        valueList.map(converter.get.apply)
  }

}
