package org.rejna.cryo.web

import java.util.Date
import java.io.{ PrintWriter, StringWriter }

import akka.actor.{ ActorSystem, Actor, ActorRef }

import org.mashupbots.socko.events.HttpRequestEvent
import org.mashupbots.socko.rest._

import org.json4s.{ Formats, DefaultFormats, NoTypeHints }

import org.rejna.cryo.models._
import InventoryStatus._

case class ExceptionToStackTrace(e: Exception) {
  override def toString = {
    val writer = new StringWriter
    e.printStackTrace(new PrintWriter(writer))
    writer.toString
  }
}

case class DataStatusMock(id: String, description: String, creationDate: Date, status: EntryStatus.EntryStatus, size: Long, checksum: String)
object DataStatusMock {
  def apply(ds: DataStatus): DataStatusMock = DataStatusMock(ds.id, ds.description, ds.creationDate, ds.status, ds.size, ds.checksum)
}

case class SnapshotListTest(id: String, plop: Array[DataStatus], date: Date)
object SnapshotListTest extends RestModelMetaData {
    val modelProperties = Seq(
      RestPropertyMetaData("id", "an id", Some(AllowableValuesList(List("A", "B", "C")))),
      RestPropertyMetaData("plop", "hum ..."))
  }
//date: Date, status: String, snapshots: List[DataStatusMock]
case class GetSnapshotListRequest(context: RestRequestContext) extends RestRequest
case class GetSnapshotListResponse(context: RestResponseContext, snapshotList: Option[SnapshotListTest]) extends RestResponse
object GetSnapshotListRegistration extends RestRegistration {
  val method = Method.GET
  val path = "/snapshots/list"
  val requestParams = Seq.empty
  //override val customFormats = Some(new Formats {
//    val dateFormat = DefaultFormats.lossless.dateFormat
//    override val typeHints = NoTypeHints
//  })
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.restProcessor
  override val description = "Retrieve list of snapshots"
}

case class GetSnapshotRequest(context: RestRequestContext, snapshotId: String) extends RestRequest
case class GetSnapshotResponse(context: RestResponseContext, snapshot: Option[DataStatusMock]) extends RestResponse
object GetSnapshotRegistration extends RestRegistration {
  val method = Method.GET
  val path = "/snapshots/{snapshotId}"
  val requestParams = Seq(PathParam("snapshotId", "ID of snapshot that needs to be fetched"))
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.restProcessor
  override val description = "Retrieve specified list information"
}

//case class GetSnapshotFilesRequest(context: RestRequestContext, snapshotId: String, path: String) extends RestRequest
//case class GetSnapshotFilesResponse(context: RestResponseContext, files: List[FileElement]) extends RestResponse
//object GetSnapshotFilesRegistration extends RestRegistration {
//  val method = Method.GET
//  val path = "/data/snapshots/{snapshotId}/files/{path}"
//  val requestParams = PathParam("snapshotId", "ID of snapshot that needs to be fetched") ::
//    PathParam("path", "path") ::
//    Nil
//  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.restHandler
//  override val description = "Retrieve specified list information"
//}

class CryoRest(val cryoctx: CryoContext) extends CryoActor {
  def receive = cryoReceive {
    case GetSnapshotListRequest(ctx) =>
      val _sender = sender
      (cryoctx.inventory ? GetSnapshotList()) map {
        case sl: SnapshotList =>
          log.info("Receive snapshot list")
          //_sender ! GetSnapshotListResponse(ctx.responseContext, Some(sl))
          val http = RestRequestEvents.get(ctx).get.asInstanceOf[HttpRequestEvent]
          http.response.write(http.request.content.toString())
        case a: Any =>
          log.error(s"Unexpected message from inventory : ${a}")
          throw CryoError("Unexpected message from inventory", a )
      } onFailure {
        case e: Throwable =>
          log.info("Error while retreiving snapshot list", e)
          _sender ! GetSnapshotListResponse(ctx.responseContext(404), None)
        //RestErrorResponse(ctx, e)
      }

    case GetSnapshotRequest(ctx, snapshotId) =>
      val _sender = sender
      (cryoctx.datastore ? GetDataStatus(snapshotId)) map {
        case d: DataStatus => _sender ! GetSnapshotResponse(ctx.responseContext, Some(DataStatusMock(d)))
      } onFailure {
        case e: Exception => _sender ! GetSnapshotResponse(ctx.responseContext(404), None)
        //RestErrorResponse(ctx, e)
      }

//    case GetSnapshotFilesRequest(ctx, snapshotId, path) =>
//      val _sender = sender
//      cryoctx.inventory ? SnapshotGetFiles(snapshotId, path.replace('!', '/')) map {
//        case SnapshotFiles(_, _, fe) => _sender ! GetSnapshotFilesResponse(ctx.responseContext, fe)
//      } onFailure {
//        case e: Exception => _sender ! RestErrorResponse(ctx, e)
//      }
  }
}

//case class RestErrorResponse(context: RestResponseContext, message: String) extends RestResponse
//object RestErrorResponse {
//  def apply(context: RestRequestContext, error: Exception, code: Int = 500) =
//    new RestErrorResponse(context.responseContext(code, Map("error" -> error.getMessage)), ExceptionToStackTrace(error).toString)
//}
