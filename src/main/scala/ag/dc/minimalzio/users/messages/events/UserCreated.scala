package ag.dc.minimalzio.users.messages.events

import java.util.UUID
import java.time.Instant
import ag.dc.minimalzio.users.model.User
import ag.dc.minimalzio.users.model.CreationUser
import ag.dc.minimalzio.cqrs.CQRS.{AggregateIdType, GenericInitialDomainEvent}
import ag.dc.minimalzio.cqrs.CQRS.InitialDomainEvent

case class UserCreated[D2 <: Serializable](
    messageId: UUID,
    correlationId: UUID,
    createdAt: Instant,
    issuer: String,
    messageData: CreationUser,
    metadata: Map[String, D2],
    aggregateLocator: (String, AggregateIdType),
    sourceIdentifier: String,
    createdByUserName: String
  ) extends GenericInitialDomainEvent[CreationUser, D2, User] {
    val prohibitedPropagationTargets = None
    val propagationAncestors = None
    def applyPatch(aggregate: User): User = aggregate.copy(
        id = this.messageData.id.getOrElse(aggregate.id),
        name = this.messageData.name,
        email = this.messageData.email,
        createdAt = createdAt,
        createdByUserName = createdByUserName,
        lastModifiedAt = createdAt,
        lastModifiedByUserName = createdByUserName
    )
  }