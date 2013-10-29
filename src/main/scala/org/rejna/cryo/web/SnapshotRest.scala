package org.rejna.cryo.web

import scala.util.{ Success, Failure }

import java.util.Date
import java.io.{ PrintWriter, StringWriter }

import akka.actor.{ ActorSystem, Actor, ActorRef }

import org.mashupbots.socko.events.HttpRequestEvent
import org.mashupbots.socko.rest._

import org.json4s.{ Formats, DefaultFormats, NoTypeHints }

import org.rejna.cryo.models._
//import EntryStatus._

case class GetSnapshotListRequest(context: RestRequestContext) extends RestRequest
case class GetSnapshotListResponse(context: RestResponseContext, snapshotList: List[DataStatus]) extends RestResponse
object GetSnapshotListRegistration extends RestRegistration {
  val method = Method.GET
  val path = "/snapshots/list"
  val requestParams = Seq.empty
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

case class DeleteSnapshotRequest(context: RestRequestContext, snapshotId: String) extends RestRequest
case class DeleteSnapshotResponse(context: RestResponseContext) extends RestResponse
object DeleteSnapshotRegistration extends RestRegistration {
  val method = Method.DELETE
  val path = "/snapshots/{snapshotId}"
  val requestParams = Seq(PathParam("snapshotId", "ID of snapshot that needs to be deleted"))
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.newRestProcessor(classOf[SnapshotRestProcessor])
  override val description = "Remove the specified snapshot"
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
case class GetSnapshotFileFilterResponse(context: RestResponseContext, filter: Option[String]) extends RestResponse
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

case class AddSnapshotFileFilterRequest(context: RestRequestContext, snapshotId: String, path: String, filter: FileFilter) extends RestRequest
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

case class UpdateSnapshotFileFilterRequest(context: RestRequestContext, snapshotId: String, path: String, filter: FileFilter) extends RestRequest
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

case class CreateSnapshotRequest(context: RestRequestContext) extends RestRequest
case class CreateSnapshotResponse(context: RestResponseContext, snapshotId: Option[String]) extends RestResponse
object CreateSnapshotRegistration extends RestRegistration {
  val method = Method.POST
  val path = "/snapshots"
    val requestParams = Nil
    def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.newRestProcessor(classOf[SnapshotRestProcessor])
    override val description = "Create a new snapshot"
}

class SnapshotRestProcessor(_cryoctx: CryoContext) extends CryoActor(_cryoctx) {
  def receive = cryoReceive {
    case GetSnapshotListRequest(ctx) =>
      val _sender = sender
      (cryoctx.inventory ? GetSnapshotList()) onComplete {
        case Success(sl: SnapshotList) => _sender ! GetSnapshotListResponse(ctx.responseContext, sl.snapshots)
        case Success(o) => _sender ! GetSnapshotListResponse(ctx.responseContext(501, Map("message" -> o.toString)), List.empty[DataStatus])
        case Failure(e) => _sender ! GetSnapshotListResponse(ctx.responseContext(500, Map("message" -> e.getMessage)), List.empty[DataStatus])
      }
      context.stop(self)

    case GetSnapshotRequest(ctx, snapshotId) =>
      val _sender = sender
      (cryoctx.datastore ? GetDataStatus(snapshotId)) onComplete {
        case Success(d: DataStatus) => _sender ! GetSnapshotResponse(ctx.responseContext, Some(d))
        case Success(e: DataNotFoundError) => _sender ! GetSnapshotResponse(ctx.responseContext(404), None)
        case Success(o) => _sender ! GetSnapshotResponse(ctx.responseContext(501, Map("message" -> o.toString)), None)
        case Failure(e) => _sender ! GetSnapshotResponse(ctx.responseContext(500, Map("message" -> e.getMessage)), None)
      }
      context.stop(self)

    case DeleteSnapshotRequest(ctx, snapshotId) =>
      val _sender = sender
      (cryoctx.inventory ? DeleteSnapshot(snapshotId)) onComplete {
        case Success(SnapshotDeleted(_)) => _sender ! DeleteSnapshotResponse(ctx.responseContext)
        case Success(SnapshotNotFound(_, _, _)) => _sender ! DeleteSnapshotResponse(ctx.responseContext(404))
        case Success(o) => _sender ! DeleteSnapshotResponse(ctx.responseContext(501, Map("message" -> o.toString)))
        case Failure(o) => _sender ! DeleteSnapshotResponse(ctx.responseContext(502, Map("message" -> o.getMessage)))
      }
      
    case GetSnapshotFilesRequest(ctx, snapshotId, path) =>
      val _sender = sender
      (cryoctx.inventory ? SnapshotGetFiles(snapshotId, path.replace('!', '/'))) onComplete {
        case Success(SnapshotFiles(_, _, fe)) => _sender ! GetSnapshotFilesResponse(ctx.responseContext, fe)
        case Success(e: SnapshotNotFound) => _sender ! GetSnapshotFilesResponse(ctx.responseContext(404), List.empty[FileElement])
        case Success(o) => _sender ! GetSnapshotFilesResponse(ctx.responseContext(501, Map("message" -> o.toString)), List.empty[FileElement])
        case Failure(e) => _sender ! GetSnapshotFilesResponse(ctx.responseContext(500, Map("message" -> e.getMessage)), List.empty[FileElement])
      }
      context.stop(self)

    case GetSnapshotFileFilterRequest(ctx, snapshotId, path) =>
      val _sender = sender
      (cryoctx.inventory ? SnapshotGetFilter(snapshotId, path)) onComplete {
        case Success(SnapshotFilter(_, _, filter)) => _sender ! GetSnapshotFileFilterResponse(ctx.responseContext, filter.map(_.toString))
        case Success(e: SnapshotNotFound) => _sender ! GetSnapshotFileFilterResponse(ctx.responseContext(404), None)
        case Success(o) => _sender ! GetSnapshotFileFilterResponse(ctx.responseContext(501, Map("message" -> o.toString)), None)
        case Failure(e) => _sender ! GetSnapshotFileFilterResponse(ctx.responseContext(500, Map("message" -> e.getMessage)), None)
      }
      context.stop(self)

    case DeleteSnapshotFileFilterRequest(ctx, snapshotId, path) =>
      val _sender = sender
      (cryoctx.inventory ? SnapshotUpdateFilter(snapshotId, path, NoOne)) onComplete {
        case Success(FilterUpdated()) => _sender ! DeleteSnapshotFileFilterResponse(ctx.responseContext)
        case Success(e: SnapshotNotFound) => _sender ! DeleteSnapshotFileFilterResponse(ctx.responseContext(404))
        case Success(o) => _sender ! DeleteSnapshotFileFilterResponse(ctx.responseContext(501, Map("message" -> o.toString)))
        case Failure(e) => _sender ! DeleteSnapshotFileFilterResponse(ctx.responseContext(500, Map("message" -> e.getMessage)))
      }

    case AddSnapshotFileFilterRequest(ctx, snapshotId, path, filter) =>
      val _sender = sender
      (cryoctx.inventory ? SnapshotUpdateFilter(snapshotId, path, filter)) onComplete {
        case Success(FilterUpdated()) => _sender ! AddSnapshotFileFilterResponse(ctx.responseContext)
        case Success(e: SnapshotNotFound) => _sender ! AddSnapshotFileFilterResponse(ctx.responseContext(404))
        case Success(o) => _sender ! AddSnapshotFileFilterResponse(ctx.responseContext(501, Map("message" -> o.toString)))
        case Failure(e) => _sender ! AddSnapshotFileFilterResponse(ctx.responseContext(500, Map("message" -> e.getMessage)))
      }

    case UpdateSnapshotFileFilterRequest(ctx, snapshotId, path, filter) =>
      val _sender = sender
      (cryoctx.inventory ? SnapshotUpdateFilter(snapshotId, path, filter)) onComplete {
        case Success(FilterUpdated()) => _sender ! UpdateSnapshotFileFilterResponse(ctx.responseContext)
        case Success(e: SnapshotNotFound) => _sender ! UpdateSnapshotFileFilterResponse(ctx.responseContext(404))
        case Success(o) => _sender ! UpdateSnapshotFileFilterResponse(ctx.responseContext(501, Map("message" -> o.toString)))
        case Failure(e) => _sender ! UpdateSnapshotFileFilterResponse(ctx.responseContext(500, Map("message" -> e.getMessage)))
      }
      
    case CreateSnapshotRequest(ctx) =>
      val _sender = sender
      (cryoctx.inventory ? CreateSnapshot()) onComplete {
        case Success(SnapshotCreated(snapshotId)) => _sender ! CreateSnapshotResponse(ctx.responseContext, Some(snapshotId))
        case Success(o) => _sender ! CreateSnapshotResponse(ctx.responseContext(501, Map("message" -> o.toString)), None)
        case Failure(e) => _sender ! CreateSnapshotResponse(ctx.responseContext(500, Map("message" -> e.getMessage)), None)
      }
  }
}