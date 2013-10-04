package org.rejna.cryo.web

import java.util.Date

import akka.actor.{ ActorSystem, Actor, ActorRef }

import org.mashupbots.socko.rest._

import org.rejna.cryo.models._
import InventoryStatus._

case class ExceptionUtil(e: Exception) {
  def toStackTraceString( 
} 
case class RestErrorResponse(context: RestResponseContext, message: String) extends RestResponse {
  def this(context: RestRequestContext, error: Exception, code: Int = 500) = this(context.responseContext(code, Map("error" -> e.getMessage)), error.toStackTraceString)
}
case class GetSnapshotListRequest(context: RestRequestContext) extends RestRequest
case class GetSnapshotListResponse(context: RestResponseContext, date: Date, status: InventoryStatus, snapshots: List[DataStatus]) extends RestResponse
case class getSnapshotListError(context: RestResponseContext) extends RestResponse
object CreateUserWithArrayRegistration extends RestRegistration {
  val method = Method.GET
  val path = "/data/snapshots/list.json"
  val requestParams = Seq.empty
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.restHandler
  override val description = "Retrieve list of snapshots"
}
//case class SnapshotNotFound(id: String, message: String, cause: Throwable = null) extends InventoryError(message, cause)

class CryoRest(val cryoctx: CryoContext) extends CryoActor {
  def receive = {
    case GetSnapshotListRequest(ctx) =>
      val _sender = sender
      (cryoctx.inventory ? GetSnapshotList) map {
        case SnapshotList(date, status, snapshots) =>
          _sender ! GetSnapshotListResponse(ctx.responseContext, date, status, snapshots)
          // ?? context.stop(self)
      } onFailure {
        case e => _sender ! getSnapshotListError(ctx.responseContext(500, Map("error" -> e.getMessage)))
      }
      
  }
  
  /*
   *  case GET(PathSegments("data" :: "snapshots" :: snapshotId :: Nil)) =>
        val sid = if (snapshotId.endsWith(".json")) snapshotId.dropRight(5) else snapshotId
        if (sid == "list") {
          (cryoctx.inventory ? GetSnapshotList) map {
            case l: SnapshotList => request
          }

        }
      case GET(PathSegments("data" :: "snapshots" :: snapshotId :: "files" :: file :: Nil)) =>
   * 
   */
}