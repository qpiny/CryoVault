package org.rejna.cryo.web

import java.util.Date
import java.io.{ PrintWriter, StringWriter }

import akka.actor.{ ActorSystem, Actor, ActorRef }

import org.mashupbots.socko.rest._

import org.rejna.cryo.models._
import InventoryStatus._

case class ExceptionToStackTrace(e: Exception) {
  override def toString = {
    val writer = new StringWriter
    e.printStackTrace(new PrintWriter(writer))
    writer.toString
  }
}
case class RestErrorResponse(context: RestResponseContext, message: String) extends RestResponse
object RestErrorResponse {
  def apply(context: RestRequestContext, error: Exception, code: Int = 500) =
    new RestErrorResponse(context.responseContext(code, Map("error" -> error.getMessage)), ExceptionToStackTrace(error).toString)
}

case class GetSnapshotListRequest(context: RestRequestContext) extends RestRequest
case class GetSnapshotListResponse(context: RestResponseContext, date: Date, status: InventoryStatus, snapshots: List[DataStatus]) extends RestResponse
object GetSnapshotListRegistration extends RestRegistration {
  val method = Method.GET
  val path = "/data/snapshots/list.json"
  val requestParams = Seq.empty
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.restHandler
  override val description = "Retrieve list of snapshots"
}

case class GetSnapshotRequest(context: RestRequestContext, snapshotId: String) extends RestRequest
case class GetSnapshotResponse(context: RestResponseContext, snapshot: DataStatus) extends RestResponse
object GetSnapshotRegistration extends RestRegistration {
  val method = Method.GET
  val path = "/data/snapshots/{snapshotId}.json"
  val requestParams = Seq(PathParam("snapshotId", "ID of snapshot that needs to be fetched"))
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.restHandler
  override val description = "Retrieve specified list information"
}

case class GetSnapshotFilesRequest(context: RestRequestContext, snapshotId: String, path: String) extends RestRequest
case class GetSnapshotFilesResponse(context: RestResponseContext, files: List[FileElement]) extends RestResponse
object GetSnapshotFilesRegistration extends RestRegistration {
  val method = Method.GET
  val path = "/data/snapshots/{snapshotId}/files/{path}.json"
  val requestParams = PathParam("snapshotId", "ID of snapshot that needs to be fetched") ::
    PathParam("path", "path") ::
    Nil
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.restHandler
  override val description = "Retrieve specified list information"
}

class CryoRest(val cryoctx: CryoContext) extends CryoActor {
  def receive = {
    case GetSnapshotListRequest(ctx) =>
      val _sender = sender
      (cryoctx.inventory ? GetSnapshotList) map {
        case SnapshotList(date, status, snapshots) =>
          _sender ! GetSnapshotListResponse(ctx.responseContext, date, status, snapshots)
      } onFailure {
        case e: Exception => _sender ! RestErrorResponse(ctx, e)
      }

    case GetSnapshotRequest(ctx, snapshotId) =>
      val _sender = sender
      (cryoctx.datastore ? GetDataStatus(snapshotId)) map {
        case d: DataStatus => _sender ! GetSnapshotResponse(ctx.responseContext, d)
      } onFailure {
        case e: Exception => _sender ! RestErrorResponse(ctx, e)
      }

    case GetSnapshotFilesRequest(ctx, snapshotId, path) =>
      val _sender = sender
      cryoctx.inventory ? SnapshotGetFiles(snapshotId, path.replace('!', '/')) map {
        case SnapshotFiles(_, _, fe) => _sender ! GetSnapshotFilesResponse(ctx.responseContext, fe)
      } onFailure {
        case e: Exception => _sender ! RestErrorResponse(ctx, e)
      }
  }
}