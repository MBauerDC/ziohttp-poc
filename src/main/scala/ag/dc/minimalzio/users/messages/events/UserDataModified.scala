package ag.dc.minimalzio.users.messages.events

import java.util.UUID
import java.time.Instant
import ag.dc.minimalzio.users.model.User
import ag.dc.minimalzio.users.model.ModificationUser
import ag.dc.minimalzio.cqrs.CQRS.{AggregateIdType, GenericInitialDomainEvent}
import ag.dc.minimalzio.cqrs.CQRS.GenericDomainEvent

case class UserDataModified[D2 <: Serializable](
    messageId: UUID,
    correlationId: UUID,
    createdAt: Instant,
    issuer: String,
    messageData: ModificationUser,
    metadata: Map[String, D2],
    aggregateLocator: (String, AggregateIdType),
    sourceIdentifier: String,
    createdByUserName: String,
    prohibitedPropagationTargets: Option[List[String] | "All"],
    propagationAncestors: Option[List[(String, Instant)]] = None
  ) extends GenericDomainEvent[ModificationUser, D2, User] {
    def applyPatch(aggregate: User): User = 
      val name = messageData.name.getOrElse(aggregate.name)
      aggregate.copy(
        name = this.messageData.name.getOrElse(aggregate.name),
        email = this.messageData.email.getOrElse(aggregate.email),
        lastModifiedAt = createdAt,
        lastModifiedByUserName = createdByUserName
    )
  }