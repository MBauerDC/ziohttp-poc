package ag.dc.minimalzio.users.handlers

import ag.dc.minimalzio.users.model.User
import ag.dc.minimalzio.cqrs.CQRS.QueryHandler
import ag.dc.minimalzio.users.messages.queries.GetUserById
import ag.dc.minimalzio.users.messages.queries.GetAllUsers
import zio.Ref
import zhttp.http.*
import java.util.UUID
import zio.ZIO
import java.time.Instant
import zio.json.JsonEncoder

class UserQueryHandler(map: Ref[Map[UUID, User]]) extends QueryHandler[(GetUserById[?] | GetAllUsers[?]), Response] {
    def handleQueryZIO(query: GetUserById[?] | GetAllUsers[?]): ZIO[Any, Throwable, Response] = 
      query match {
        case create:GetUserById[?] => handleGetById(create)
        case update:GetAllUsers[?] => handleGetAll(update)
      }

    def handleGetById(query: GetUserById[?]): ZIO[Any, Throwable, Response] = 
      for {
        umap <- map.get
        uid = query.userId
        r = if umap.contains(uid)
            then Response.text(User.given_JsonEncoder_User.encodeJson(umap(uid), None))
            else Response.status(Status.NotFound)
      } yield r
    
    def handleGetAll(query: GetAllUsers[?])(using enc:JsonEncoder[List[User]]): ZIO[Any, Throwable, Response] = 
      for {
        umap <- map.get
        r = Response.text(
              enc.encodeJson(umap.toList.map(_._2), None)
            )
      } yield r
      
  }