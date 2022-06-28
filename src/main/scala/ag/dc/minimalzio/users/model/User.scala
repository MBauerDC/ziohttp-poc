package ag.dc.minimalzio.users.model

import java.util.UUID
import java.time.Instant
import ag.dc.minimalzio.cqrs.CQRS.Aggregate
import zio.json.*

case class User(
  id: UUID, 
  name: String, 
  email: String, 
  createdAt: Instant, 
  createdByUserName: String, 
  lastModifiedAt: Instant, 
  lastModifiedByUserName: String
) extends Aggregate { 
  type IdType = UUID; 
  val state = "Open" 
}
object User {
  given JsonDecoder[User] = DeriveJsonDecoder.gen[User]
  given JsonEncoder[User] = DeriveJsonEncoder.gen[User]
}

case class CreationUser(
  id: Option[UUID] = None, 
  name: String, 
  email: String
)
object CreationUser {
  given JsonEncoder[CreationUser] = DeriveJsonEncoder.gen[CreationUser]
  given JsonDecoder[CreationUser] = DeriveJsonDecoder.gen[CreationUser]
}

case class ModificationUser(
  name: Option[String] = None, 
  email: Option[String] = None, 
)
object ModificationUser {
  given JsonEncoder[ModificationUser] = DeriveJsonEncoder.gen[ModificationUser]
  given JsonDecoder[ModificationUser] = DeriveJsonDecoder.gen[ModificationUser]
}

case class UserData(
  id: Option[UUID] = None, 
  name: Option[String] = None, 
  email: Option[String] = None, 
  createdAt: Option[Instant] = None, 
  createdByUserName: Option[String] = None, 
  lastModifiedAt: Option[Instant] = None, 
  lastModifiedByUserName: Option[String] = None
)
object UserData {
  given JsonEncoder[UserData] = DeriveJsonEncoder.gen[UserData]
  given JsonDecoder[UserData] = DeriveJsonDecoder.gen[UserData]
}
  