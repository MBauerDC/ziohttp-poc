package ag.dc.minimalzio.cqrs

import java.util.UUID
import java.time.Instant
import zhttp.http.Method
import zhttp.http.URL
import ag.dc.minimalzio.utils.Utils.Metadata.CreationTime
import ag.dc.minimalzio.utils.Utils.Metadata.CreationUserName
import ag.dc.minimalzio.utils.Utils.Metadata.ModificationTime
import ag.dc.minimalzio.utils.Utils.Metadata.ModificationUserName
import io.netty.handler.codec.http.FullHttpMessage
import zhttp.http.Request
import ag.dc.minimalzio.utils.Utils
import ag.dc.minimalzio.utils.Utils.RequestError
import scala.util.Random.apply
import java.util.Random
import zio.Chunk
import java.lang.reflect.Field
import zhttp.http.Response
import zio.ZIO
import zhttp.http.Path
import zhttp.http.*
import zio.json.*
import scala.util.Try
import zio.Ref
import io.netty.channel.MessageSizeEstimator.Handle
import java.nio.charset.Charset
import ag.dc.minimalzio.cqrs.CQRS.DomainEventProjector

object CQRS {

  type AggregateIdType = UUID | Int

  type AggregateState = "Open" | "Closed"

  enum CommonHeaders(name: String):
    def getName = this.name
    case CorrelationId extends CommonHeaders("X-Correlation-Id")
    case SessionId extends CommonHeaders("X-Session-Id")

  trait FullMetadata extends CreationTime with CreationUserName with ModificationTime with ModificationUserName

  trait Aggregate extends FullMetadata with Product with Serializable {
    type IdType <: AggregateIdType
    val id: IdType
    val state: AggregateState
  }

  abstract trait Message[D1 <: Serializable, D2 <: Serializable] extends Product with Serializable {
    val messageId: UUID
    val correlationId: UUID
    val createdAt: Instant
    val issuer: String
    val messageData: D1
    val metadata: Map[String, D2]
  }

  given msgOrd[T <: Message[?,?]]: Ordering[T] with
    override def compare(x: T, y: T): Int = 
      x.createdAt.compareTo(y.createdAt)
  
  abstract trait IncomingMessage[D1 <: Serializable, D2 <: Serializable] extends Message[D1, D2] {
    val targetLocator: String | (Method, URL)
    val sourceIdentifier: String
  }

  // Implementations should be configuratble to include headerData or not
  sealed abstract trait IncomingHTTPMessage[D1 <: Serializable, D2 <: Serializable] extends Message[D1, D2] {
    val targetLocator: (Method, URL)
    val headerData: Option[zhttp.http.Headers]
  }

  trait Command[D1 <: Serializable,D2 <: Serializable] extends IncomingHTTPMessage[D1, D2]
  trait Query[D1 <: Serializable,D2 <: Serializable] extends IncomingHTTPMessage[D1, D2] {}

  trait CommandFactory {
    val requestToCommand: PartialFunction[Request, ZIO[Any, Throwable, Either[RequestError, Command[?, ?]]]]
    def registerCommands[C <: Command[?, ?]](pfs: List[PartialFunction[Request, ZIO[Any, Throwable, Either[RequestError, C]]]]):  CommandFactory
  }

  trait QueryFactory {
    val requestToQuery: PartialFunction[Request, ZIO[Any, Throwable, Either[RequestError, Query[?, ?]]]]
    def registerQueries[C <: Query[?, ?]](pfs: List[PartialFunction[Request, ZIO[Any, Throwable, Either[RequestError, C]]]]): QueryFactory
  }

  class GenericCommandFactory(
    val registeredCommands: List[PartialFunction[Request, ZIO[Any, Throwable, Either[RequestError, Command[?, ?]]]]] = List()
  ) extends CommandFactory {
    // OR the individual PartialFunctions, then lift to a total function to Option
    val composedFactories = registeredCommands.reduce((a, b) => a.orElse(b))
    def registerCommands[C <: Command[?, ?]](pfs: List[PartialFunction[Request, ZIO[Any, Throwable, Either[RequestError, C]]]]):  GenericCommandFactory = 
      new GenericCommandFactory(registeredCommands.appendedAll(pfs))
    val requestToCommand: PartialFunction[Request, ZIO[Any, Throwable, Either[RequestError, Command[?, ?]]]] =
      composedFactories
  }

  class GenericQueryFactory(
    val registeredQueries: List[PartialFunction[Request, ZIO[Any, Throwable, Either[RequestError, Query[?, ?]]]]] = List()
  ) extends QueryFactory {
    val composedFactories = registeredQueries.reduce((a, b) => a.orElse(b))
    def registerQueries[Q <: Query[?, ?]](pfs: List[PartialFunction[Request, ZIO[Any, Throwable, Either[RequestError, Q]]]]):  GenericQueryFactory = 
      new GenericQueryFactory(registeredQueries.appendedAll(pfs))
    val requestToQuery: PartialFunction[Request, ZIO[Any, Throwable, Either[RequestError, Query[?, ?]]]] =
      composedFactories
  }

  trait IncomingHTTPMessageFactory {
    val queryFactory: QueryFactory
    val commandFactory: CommandFactory
    val notFoundError = ZIO.succeed(Left(RequestError(Status.NotFound, "")))
    val requestToMessage: PartialFunction[Request, ZIO[Any, Throwable, Either[RequestError, IncomingHTTPMessage[?, ?]]]] = {
      case r:Request if (r.method.equals(Method.GET))   => queryFactory.requestToQuery(r)
      case r:Request if (r.method.equals(Method.PUT))   => commandFactory.requestToCommand(r)
      case r:Request if (r.method.equals(Method.PATCH)) => commandFactory.requestToCommand(r)
      case r:Request if (r.method.equals(Method.POST))  => commandFactory.requestToCommand(r)
    }
  }

  class GenericIncomingHTTPMessageFactory(
    val queryFactory: QueryFactory,
    val commandFactory: CommandFactory
  ) extends IncomingHTTPMessageFactory

  trait CommandHandler[CommandTypeUnion <: Command[?, ?], R <: Serializable] {
    type HandledCommands = CommandTypeUnion
    def handles[T <: Command[?, ?]](c: T): Boolean = c match {
      case _:HandledCommands => true
      case _  => false
    }
    def handleCommandZIO(command: CommandTypeUnion): ZIO[Any, Throwable, R]
  }

  trait QueryHandler[QueryTypeUnion <: Query[?, ?], R <: Serializable] {
    type HandledQueries = QueryTypeUnion
    def handles[T <: Query[?, ?]](c: T): Boolean = c match {
      case _:HandledQueries => true
      case _  => false
    }
    def handleQueryZIO(query: QueryTypeUnion): ZIO[Any, Throwable, R]
  }

  trait IncomingHTTPMessageDispatcher[R <: Serializable](
    protected val queryHandlers: List[QueryHandler[?, R]], 
    protected val commandHandlers: List[CommandHandler[?, R]],
    protected val defaultNotFound: R,
    protected val reqErrorToR: RequestError => R = (r:RequestError) => r.toResponse()
  ) {
    def handles(message: Query[?, ?] | Command[?, ?]): Boolean = 
      message match {
        case q: Query[?, ?] => queryHandlers.exists(_.handles(q))
        case c: Command[?, ?] => commandHandlers.exists(_.handles(c))
      }
    def dispatchZIO(message: Query[?, ?] | Command[?, ?]): ZIO[Any, Throwable, R] = 
      val opt = message match {
        case q: Query[?, ?] => queryHandlers.find(_.handles(q)).map(h => h.handleQueryZIO(q.asInstanceOf[h.HandledQueries]))
        case c: Command[?, ?] => commandHandlers.find(_.handles(c)).map(h => h.handleCommandZIO(c.asInstanceOf[h.HandledCommands]))
      }
      opt match {
        case Some(z) => z
        case None => ZIO.succeed(defaultNotFound)
      }
  }

  class GenericIncomingHTTPMessageDispatcher(
    queryHandlers: List[QueryHandler[?, Response]] = List(),
    commandHandlers: List[CommandHandler[?, Response]] = List(),
    defaultNotFound: Response = Response.status(Status.NotFound),
    reqErrorToR: RequestError => Response = (r:RequestError) => r.toResponse()
  ) extends IncomingHTTPMessageDispatcher[Response](queryHandlers, commandHandlers, defaultNotFound) {
    def registerHandler(handler: CommandHandler[?, Response] | QueryHandler[?, Response]): GenericIncomingHTTPMessageDispatcher = 
      handler match {
        case qh:QueryHandler[?, Response] => 
          new GenericIncomingHTTPMessageDispatcher(
            queryHandlers.appended(qh), 
            commandHandlers, 
            defaultNotFound
          )
        case ch:CommandHandler[?, Response] => 
          new GenericIncomingHTTPMessageDispatcher(
            queryHandlers, 
            commandHandlers.appended(ch), 
            defaultNotFound
          )
      }
  }

  abstract trait OutgoingMessage[D1 <: Serializable, D2 <: Serializable] extends Message[D1, D2] {
    val sourceIdentifier: String
  }
  
  // Implementations should be configurable to include propagationAncestors or not
  trait Event[D1 <: Serializable,D2 <: Serializable] extends OutgoingMessage[D1, D2] {
    val propagationAncestors: Option[List[(String, Instant)]]
    val prohibitedPropagationTargets: Option[List[String] | "All"]
  }

  trait DomainEvent[D1 <: Serializable,D2 <: Serializable, A <: Aggregate] extends Event[D1, D2] {
    type AggregateType = A
    val isInitialEvent = false
    val aggregateLocator: (String, AggregateIdType)
    def applyPatch(aggregate: A): A
  }

  abstract class GenericDomainEvent[D1 <: Serializable,D2 <: Serializable, C <: Aggregate] 
  extends DomainEvent[D1, D2, C] {
   val messageId: UUID
   val correlationId: UUID
   val createdAt: Instant
   val issuer: String
   val messageData: D1
   val metadata: Map[String, D2]
   val aggregateLocator: (String, AggregateIdType)
   val sourceIdentifier: String
  }

  trait InitialDomainEvent[D1 <: Serializable, D2 <: Serializable, A <: Aggregate] extends DomainEvent[D1, D2, A] {
    override val isInitialEvent = true
  }

  abstract class GenericInitialDomainEvent[D1 <: Serializable, D2 <: Serializable, C <: Aggregate] 
  extends InitialDomainEvent[D1, D2, C] {
    val messageId: UUID
    val correlationId: UUID
    val createdAt: Instant
    val issuer: String
    val messageData: D1
    val metadata: Map[String, D2]
    val aggregateLocator: (String, AggregateIdType)
    val sourceIdentifier: String
  }


  trait IntegrationEvent[D1 <: Serializable, D2 <: Serializable] extends Event[D1, D2]

  class DomainEventProjector[C <: Aggregate] {
    type TargetAggregate = C
    
    def projectEvents
      (baseAggregate: TargetAggregate,
      events: List[DomainEvent[?,?,TargetAggregate]]): TargetAggregate = 

      val evtsAfter = baseAggregate.lastModifiedAt
      val filteredAndSorted = 
          events
          .filter(_.createdAt.isAfter(evtsAfter))
          .sorted
      
      filteredAndSorted.foldLeft(baseAggregate)((a, e) => e.applyPatch(a))

    def projectEvents[D1 <: Serializable, M1 <: Serializable, C <: Aggregate, D2 <: Serializable, M2 <: Serializable]
      (generator: InitialDomainEvent[D1, M1, TargetAggregate] => TargetAggregate,
       events: (InitialDomainEvent[D1, M1, TargetAggregate], List[DomainEvent[D2, M2 ,TargetAggregate]])): TargetAggregate =

      val initialEvent = events._1
      val otherEvents = events._2
      val filteredAndSorted = 
        otherEvents
        .filter(e => !e.isInitialEvent && e.createdAt.isAfter(initialEvent.createdAt))
        .sorted
    
      val initialAggregate = generator(initialEvent)
      filteredAndSorted.foldLeft(initialAggregate)((a, e) => e.applyPatch(a))
  }

  object DomainEventProjector {
    def apply[C <: Aggregate]() = new DomainEventProjector[C]
  }

  
  case class SimpleField[T](name: String) {
    type ValueType = T
  }

  object SimpleField {
    def apply[T](name: String) = new SimpleField[T](name)
    def unapply[T](f: SimpleField[T]) = 
      Some((f.name, f.getClass.getTypeParameters()(0)))
  }

  case class SimpleSchema[T](fields: List[SimpleField[?]]) {
    type ObjectType = T
  }

  object SimpleSchema {
    def apply[T](fields: List[SimpleField[?]]) = new SimpleSchema[T](fields)
    def unapply[T](s: SimpleSchema[T]): Option[List[SimpleField[?]]] = 
      Some(s.fields)
  }


 }
