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
import scala.util.Random.apply
import java.util.Random
import zio.Chunk
import java.lang.reflect.Field
import zhttp.http.Response
import zio.ZIO
import zhttp.http.Path
import zhttp.http.*
import ag.dc.minimalzio.cqrs.CQRS.User.UserData
import zio.json.*
import scala.util.Try
import ag.dc.minimalzio.cqrs.CQRS.User.CreationUser
import ag.dc.minimalzio.cqrs.CQRS.User.ModificationUser
import zio.Ref
import io.netty.channel.MessageSizeEstimator.Handle

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
    val id: UUID
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
  abstract trait IncomingHTTPMessage[D1 <: Serializable, D2 <: Serializable] extends Message[D1, D2] {
    val targetLocator: (Method, URL)
    val headerData: Option[zhttp.http.Headers]
    def fromRequest(req: Request): IncomingHTTPMessage[D1, D2]
  }


  trait Command[D1 <: Serializable,D2 <: Serializable] extends IncomingHTTPMessage[D1, D2]
  trait Query[D1 <: Serializable,D2 <: Serializable] extends IncomingHTTPMessage[D1, D2] {}

  trait CommandFactory {
    def requestToCommand(req: Request) : Option[ZIO[Any, Throwable, Command[?, ?]]]
    def registerCommands[C <: Command[?, ?]](pfs: List[PartialFunction[Request, ZIO[Any, Throwable, C]]]):  CommandFactory
  }

  trait QueryFactory {
    def requestToQuery(req: Request) : Option[ZIO[Any, Throwable, Query[?, ?]]]
    def registerQueries[C <: Command[?, ?]](pfs: List[PartialFunction[Request, ZIO[Any, Throwable, C]]]): QueryFactory
  }

  trait IncomingHTTPMessageFactory {
    val queryFactory: QueryFactory
    val commandFactory: CommandFactory
    val notFoundQuery: Query[?, ?]
    def requestToMessage(req: Request): ZIO[Any, Throwable, IncomingHTTPMessage[?, ?]] =
      val messageOption: Option[ZIO[Any, Throwable, Command[?, ?] | Query[?, ?]]] = req.method match {
        case Method.GET   => queryFactory.requestToQuery(req)
        case Method.PUT   => commandFactory.requestToCommand(req)
        case Method.PATCH => commandFactory.requestToCommand(req)
        case Method.POST  => commandFactory.requestToCommand(req)
        case _            => Some(ZIO.succeed(notFoundQuery))
      }
      messageOption match {
        case Some(z: ZIO[Any, Throwable, IncomingHTTPMessage[?, ?]]) => z
        case None => ZIO.succeed(notFoundQuery)
      }
  }

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

  trait IncomingHTTPMessageDispatcher[R <: Serializable](defaultNotFound: R) {
    val queryHandlers: List[QueryHandler[?, R]]
    val commandHandlers: List[CommandHandler[?, R]]
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
      opt.getOrElse(
        ZIO.succeed(defaultNotFound)
      )
  }

  class GenericIncomingHTTPMessageDispatcher[R <: Serializable](
    queryHandlers: List[QueryHandler[?, R]] = List(),
    commandHandlers: List[CommandHandler[?, R]] = List(),
    defaultNotFound: R
  ) extends IncomingHTTPMessageDispatcher[R](defaultNotFound) {
    def registerHandler(handler: CommandHandler[?, R] | QueryHandler[?, R]): Unit = 
      handler match {
        case qh:QueryHandler[?,R] => 
          new GenericIncomingHTTPMessageDispatcher[R](
            queryHandlers.appended(qh), 
            commandHandlers, 
            defaultNotFound
          )
        case ch:CommandHandler[?,R] => 
          new GenericIncomingHTTPMessageDispatcher[R](
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
    val aggregateLocator: (String)
    def applyPatch(aggregate: A): A
  }

  case class GenericDomainEvent[D1 <: Serializable,D2 <: Serializable, C <: Aggregate](
    id: UUID,
    correlationId: UUID,
    createdAt: Instant,
    issuer: String,
    messageData: D1,
    metadata: Map[String, D2],
    aggregateLocator: (String, AggregateIdType),
    sourceIdentifier: String
  ) extends DomainEvent[D1, D2, C] {
    type AggregateType = C
  }

  trait InitialDomainEvent[D1 <: Serializable, D2 <: Serializable, A <: Aggregate] extends DomainEvent[D1, D2, A] {
    override val isInitialEvent = true
  }

  abstract case class GenericInitialDomainEvent[D1 <: Serializable, D2 <: Serializable, C <: Aggregate](
    id: UUID,
    correlationId: UUID,
    createdAt: Instant,
    issuer: String,
    messageData: D1,
    metadata: Map[String, D2],
    aggregateLocator: (String, AggregateIdType),
    sourceIdentifier: String
  ) extends InitialDomainEvent[D1, D2, C]


  trait IntegrationEvent[D1 <: Serializable, D2 <: Serializable] extends Event[D1, D2]

  class DomainEventProjector[C <: Aggregate] {
    type TargetAggregate = C
    
    def projectEvents
      (baseAggregate: C,
      events: List[DomainEvent[?,?,C]]): C = 

      val evtsAfter = baseAggregate.lastModifiedAt
      val filteredAndSorted = 
          events
          .filter(_.createdAt.isAfter(evtsAfter))
          .sorted
      
      filteredAndSorted.foldLeft(baseAggregate)((a, e) => e.applyPatch(a))

    def projectEvents[D1 <: Serializable, M1 <: Serializable, C <: Aggregate, D2 <: Serializable, M2 <: Serializable]
      (generator: InitialDomainEvent[D1, M1, C] => C,
       events: (InitialDomainEvent[D1, M1, C], List[DomainEvent[D2, M2 ,C]])): C =

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
    type FieldName = "id" | "name" | "email" | "createdAt" | "createdByUserName" | "lastModifiedAt" | "lastModifiedByUserName"

    val fieldTuple = (
      SimpleField[UUID]("id"),
      SimpleField[String]("name"),
      SimpleField[String]("email"),
      SimpleField[Instant]("createdAt"),
      SimpleField[String]("createdByUserName"),
      SimpleField[Instant]("lastModifiedAt"),
      SimpleField[String]("lastModifiedByUserName")
    )
    val (id, name, email, createdAt, createdByUserName, lastModifiedAt, lastModifiedByUserName) = 
      fieldTuple
    val fieldList = List(
      id, name, email, createdAt, createdByUserName, lastModifiedAt, lastModifiedByUserName
    )
    val schema = SimpleSchema[User](fieldList)
    val fieldTypes = fieldList.map(_.getClass.getTypeParameters()(0))
    
    val fieldMap: Map[FieldName, SimpleField[?]] = Map(
      "id" -> id,
      "name" -> name,
      "email" -> email,
      "createdAt" -> createdAt,
      "createdByUserName" -> createdByUserName,
      "lastModifiedAt" -> lastModifiedAt,
      "lastModifiedByUserName" -> lastModifiedByUserName
    )

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

  }

  val ti = DomainEventProjector[User]()

  val userId = UUID.randomUUID

  val userData = User.UserData().copy(
      id = Some(userId), 
      name = Some("Max Mustermann"),
      email = Some("max@mustermann.de"),
      createdAt = Some(Instant.now),
      createdByUserName = Some("Michael Bauer"),
      lastModifiedAt = Some(Instant.now),
      lastModifiedByUserName = Some("Michael Bauer")
    )

  val initialEvent = new GenericInitialDomainEvent[User.UserData, Nothing, User](
    UUID.randomUUID,
    UUID.randomUUID,
    Instant.now,
    "ziodemo",
    userData,
    Map(),
    (User.getClass.getName, userId),
    "Browser"
  ) {
    val prohibitedPropagationTargets = None
    val propagationAncestors = None
    def applyPatch(aggregate: User): User = aggregate.copy(
        id = this.messageData.id.getOrElse(aggregate.id),
        name = this.messageData.name.getOrElse(aggregate.name),
        email = this.messageData.email.getOrElse(aggregate.email),
        createdAt = this.messageData.createdAt.getOrElse(aggregate.createdAt),
        createdByUserName = this.messageData.createdByUserName.getOrElse(aggregate.createdByUserName),
        lastModifiedAt = this.messageData.lastModifiedAt.getOrElse(aggregate.lastModifiedAt),
        lastModifiedByUserName = this.messageData.lastModifiedByUserName.getOrElse(aggregate.lastModifiedByUserName)
    )
  }

  val gen = 
    (i: InitialDomainEvent[User.UserData, ?, User]) =>
        User(
          i.messageData.id.get,
          i.messageData.name.get,
          i.messageData.email.get,
          i.messageData.createdAt.get,
          i.messageData.createdByUserName.get,
          i.messageData.lastModifiedAt.get,
          i.messageData.lastModifiedByUserName.get
        )
    
  val mappedUser = 
    ti.projectEvents(
      gen, 
      (initialEvent, List[CQRS.DomainEvent[UUID | String | Instant, Nothing,User]]())
      )
  println(s"mapped user: $mappedUser")
  println(s"event: $initialEvent")

  val nameEvent = new CQRS.GenericDomainEvent[User.UserData, Nothing, User](
    UUID.randomUUID,
    UUID.randomUUID,
    Instant.now,
    "ziodemo",
    User.UserData().copy(name = Some("Gabriele Musterfrau")),
    Map(),
    (User.getClass.getName, userId),
    "Browser"
  ) {
    val prohibitedPropagationTargets = None
    val propagationAncestors = None
    def applyPatch(aggregate: User): User = aggregate.copy(
        name = this.messageData.name.get,
    )
  }

  val changedUser = ti.projectEvents(mappedUser, List(nameEvent))
  println(s"$changedUser")

  class GenericCommandFactory(
    val registeredCommands: List[PartialFunction[Request, ZIO[Any, Throwable, Command[?, ?]]]] = List()
  ) extends CommandFactory {
    val composedFactories = registeredCommands.reduce((a, b) => a.orElse(b)).lift
    def registerCommands[C <: Command[?, ?]](pfs: List[PartialFunction[Request, ZIO[Any, Throwable, C]]]):  GenericCommandFactory = 
      new GenericCommandFactory(registeredCommands.appendedAll(pfs))
    def requestToCommand(req: Request) : Option[ZIO[Any, Throwable, Command[?, ?]]] =
      composedFactories(req)
  }

  class GenericQueryFactory(
    val registeredQueries: List[PartialFunction[Request, ZIO[Any, Throwable, Query[?, ?]]]] = List()
  ) extends QueryFactory {
    val composedFactories = registeredQueries.reduce((a, b) => a.orElse(b)).lift
    def registerQueries[Q <: Query[?, ?]](pfs: List[PartialFunction[Request, ZIO[Any, Throwable, Q]]]):  GenericQueryFactory = 
      new GenericQueryFactory(registeredQueries.appendedAll(pfs))
    def requestToQuery(req: Request) : Option[ZIO[Any, Throwable, Query[?, ?]]] =
      composedFactories(req)
  }

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

  val changeUserDataByIdCtor: PartialFunction[Request, ZIO[Any, Throwable, ChangeUserDataById[?]]] = {
    
    case req@(Method.PATCH -> !! / "users" / userId) if (Try(UUID.fromString(userId)).isSuccess) => {
      req.bodyAsString.flatMap(bodyString => 
        val errOrModificationUser = 
          ModificationUser.given_JsonDecoder_ModificationUser.decodeJson(bodyString)
        ZIO.attempt(  
          errOrModificationUser match {
            case Left(err) => throw IllegalArgumentException(err)
            case Right(user) => 
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
          }
        )
      )
    }
  }

  val createUserCtor: PartialFunction[Request, ZIO[Any, Throwable, CreateUser[?]]] = {
    
    case req@(Method.POST -> !! / "users") => {
      req.bodyAsString.flatMap(bodyString => 
        val jsonErrOrCreateUser = 
          CreationUser.given_JsonDecoder_CreationUser.decodeJson(bodyString)
        ZIO.attempt(
          jsonErrOrCreateUser match {
            case Left(err) => throw IllegalArgumentException(err)
            case Right(user) => 
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
          }
        )   
      )
    }

  }

  val commandFactory = (new CommandFactory()).registerCommands(List(createUserCtor, changeUserDataByIdCtor))

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
}
