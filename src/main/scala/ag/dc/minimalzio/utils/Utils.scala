package ag.dc.minimalzio.utils

import java.time.Instant
import zhttp.http.Request

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
