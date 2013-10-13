package org.rejna.cryo.web

import scala.util.{ Success, Failure }

import java.util.Date
import java.io.{ PrintWriter, StringWriter }

import akka.actor.{ ActorSystem, Actor, ActorRef }

import org.mashupbots.socko.events.HttpRequestEvent
import org.mashupbots.socko.rest._

import org.json4s.{ Formats, DefaultFormats, NoTypeHints }

import org.rejna.cryo.models._
import InventoryStatus._

case class GetSnapshotListRequest(context: RestRequestContext) extends RestRequest
case class GetSnapshotListResponse(context: RestResponseContext, snapshotList: Option[SnapshotList]) extends RestResponse
object GetSnapshotListRegistration extends RestRegistration {
  val method = Method.GET
  val path = "/snapshots/list"
  val requestParams = Seq.empty
  //override val customFormats = Some(new Formats {
  //    val dateFormat = DefaultFormats.lossless.dateFormat
  //    override val typeHints = NoTypeHints
  //  })
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.newRestProcessor(classOf[SnapshotRestProcessor])
  override val description = "Retrieve list of snapshots"
}

case class GetSnapshotRequest(context: RestRequestContext, snapshotId: String) extends RestRequest
case class GetSnapshotResponse(context: RestResponseContext, snapshot: Option[DataStatus]) extends RestResponse
object GetSnapshotRegistration extends RestRegistration {
  val method = Method.GET
  val path = "/snapshots/{snapshotId}"
  val requestParams = Seq(PathParam("snapshotId", "ID of snapshot that needs to be fetched"))
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.newRestProcessor(classOf[SnapshotRestProcessor])
  override val description = "Retrieve specified list information"
}

case class GetSnapshotFilesRequest(context: RestRequestContext, snapshotId: String, path: String) extends RestRequest
case class GetSnapshotFilesResponse(context: RestResponseContext, files: List[FileElement]) extends RestResponse
object GetSnapshotFilesRegistration extends RestRegistration {
  val method = Method.GET
  val path = "/snapshots/{snapshotId}/files/{path}"
  val requestParams = PathParam("snapshotId", "ID of snapshot that needs to be fetched") ::
    PathParam("path", "path") ::
    Nil
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.newRestProcessor(classOf[SnapshotRestProcessor])
  override val description = "Retrieve specified list information"
}

case class GetSnapshotFileFilterRequest(context: RestRequestContext, snapshotId: String, path: String) extends RestRequest
case class GetSnapshotFileFilterResponse(context: RestResponseContext, filter: String) extends RestResponse
object GetSnapshotFileFilterRegistration extends RestRegistration {
  val method = Method.GET
  val path = "/snapshots/{snapshotId}/filter/{path}"
  val requestParams = PathParam("snapshotId", "ID of snapshot that needs to be fetched") ::
    PathParam("path", "path") ::
    Nil
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.newRestProcessor(classOf[SnapshotRestProcessor])
  override val description = "Retrieve filter of the specified path"
}

case class DeleteSnapshotFileFilterRequest(context: RestRequestContext, snapshotId: String, path: String) extends RestRequest
case class DeleteSnapshotFileFilterResponse(context: RestResponseContext) extends RestResponse
object DeleteSnapshotFileFilterRegistration extends RestRegistration {
  val method = Method.DELETE
  val path = "/snapshots/{snapshotId}/filter/{path}"
  val requestParams = PathParam("snapshotId", "ID of snapshot that needs to be fetched") ::
    PathParam("path", "path") ::
    Nil
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.newRestProcessor(classOf[SnapshotRestProcessor])
  override val description = "Remove filter of the specified path"
}

case class AddSnapshotFileFilterRequest(context: RestRequestContext, snapshotId: String, path: String) extends RestRequest
case class AddSnapshotFileFilterResponse(context: RestResponseContext) extends RestResponse
object AddSnapshotFileFilterRegistration extends RestRegistration {
  val method = Method.POST
  val path = "/snapshots/{snapshotId}/filter/{path}"
  val requestParams = PathParam("snapshotId", "ID of snapshot that needs to be fetched") ::
    PathParam("path", "path") ::
    BodyParam("filter") ::
    Nil
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.newRestProcessor(classOf[SnapshotRestProcessor])
  override val description = "Remove filter of the specified path"
}

case class UpdateSnapshotFileFilterRequest(context: RestRequestContext, snapshotId: String, path: String) extends RestRequest
case class UpdateSnapshotFileFilterResponse(context: RestResponseContext) extends RestResponse
object UpdateSnapshotFileFilterRegistration extends RestRegistration {
  val method = Method.PUT
  val path = "/snapshots/{snapshotId}/filter/{path}"
  val requestParams = PathParam("snapshotId", "ID of snapshot that needs to be fetched") ::
    PathParam("path", "path") ::
    BodyParam("filter") ::
    Nil
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.newRestProcessor(classOf[SnapshotRestProcessor])
  override val description = "Remove filter of the specified path"
}

class SnapshotRestProcessor(val cryoctx: CryoContext) extends CryoActor {
  def receive = cryoReceive {
    case GetSnapshotListRequest(ctx) =>
      val _sender = sender
      (cryoctx.inventory ? GetSnapshotList()) onComplete {
        case Success(sl: SnapshotList) => _sender ! GetSnapshotListResponse(ctx.responseContext, Some(sl))
        case Success(o) => _sender ! GetSnapshotListResponse(ctx.responseContext(501, Map("message" -> o.toString)), None)
        case Failure(e) => _sender ! GetSnapshotListResponse(ctx.responseContext(500, Map("message" -> e.getMessage)), None)
      }
      context.stop(self)

    case GetSnapshotRequest(ctx, snapshotId) =>
      val _sender = sender
      (cryoctx.datastore ? GetDataStatus(snapshotId)) onComplete {
        case Success(d: DataStatus) => _sender ! GetSnapshotResponse(ctx.responseContext, Some(d))
        case Success(DataNotFoundError) => _sender ! GetSnapshotResponse(ctx.responseContext(404), None)
        case Success(o) => _sender ! GetSnapshotListResponse(ctx.responseContext(501, Map("message" -> o.toString)), None)
        case Failure(e) => _sender ! GetSnapshotListResponse(ctx.responseContext(500, Map("message" -> e.getMessage)), None)
      }
      context.stop(self)

    case GetSnapshotFilesRequest(ctx, snapshotId, path) =>
      val _sender = sender
      (cryoctx.inventory ? SnapshotGetFiles(snapshotId, path.replace('!', '/'))) onComplete {
        case Success(SnapshotFiles(_, _, fe)) => _sender ! GetSnapshotFilesResponse(ctx.responseContext, fe)
        case Success(SnapshotNotFound) => _sender ! GetSnapshotFilesResponse(ctx.responseContext(404), List.empty[FileElement])
        case Success(o) => _sender ! GetSnapshotFilesResponse(ctx.responseContext(501, Map("message" -> o.toString)), List.empty[FileElement])
        case Failure(e) => _sender ! GetSnapshotFilesResponse(ctx.responseContext(500, Map("message" -> e.getMessage)), List.empty[FileElement])
      }
      context.stop(self)
  }
}
