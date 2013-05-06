package org.rejna.cryo.web

import java.io.File
import scala.concurrent.duration._
import net.liftweb.json._
import org.rejna.cryo.models.{Archive, ArchiveType, CryoStatus, Snapshot}

abstract class Event
abstract class RequestEvent extends Event {
  //  var responseChannel: Option[PushEnumerator[ResponseEvent]] = None
  //  def withChannel(c: PushEnumerator[ResponseEvent]): RequestEvent = {
  //    responseChannel = Some(c)
  //    this
  //  }
}
abstract class ResponseEvent(val path: String) extends Event

case class GetArchiveList() extends RequestEvent
case class ArchiveList(archives: List[Archive]) extends ResponseEvent("<null>")
case class SnapshotList(snapshots: List[Snapshot]) extends ResponseEvent("<null>")

case class GetSnapshotList() extends RequestEvent
case class Subscribe(subscription: String) extends RequestEvent
case class Unsubscribe(subscription: String) extends RequestEvent
case class AddIgnoreSubscription(subscription: String) extends RequestEvent
case class RemoveIgnoreSubscription(subscription: String) extends RequestEvent
case class CreateSnapshot() extends RequestEvent
case class UploadSnapshot(snapshotId: String) extends RequestEvent
case class AddFile(snapshotId: String, file: String) extends RequestEvent
case class SnapshotCreated(id: String) extends ResponseEvent("<null>")
case class ArchiveCreation(file: File, archiveType: ArchiveType.ArchiveType, id: String, state: CryoStatus.CryoStatus) extends ResponseEvent(id)
case class RefreshInventory(maxAge: Duration = 24 hours) extends RequestEvent

case class Error(message: String) extends ResponseEvent("<null>")
case class GetSnapshotFiles(snapshotId: String, directory: String) extends RequestEvent
class FileElement(file: File, count: Int, size: Long, filter: Option[String])
class SnapshotFiles(snapshotId: String, directory: String, fileElements: List[FileElement]) extends ResponseEvent("<null>")
case class UpdateSnapshotFileFilter(snapshotId: String, directory: String, filter: String) extends RequestEvent
/*
case class InventoryRequest() extends RequestEvent {
  def toJson = Json.toJson(Map("type" -> "inventory"))
}
case class UpdateSnapshotList(archives: List[RemoteArchive]) extends ResponseEvent {
  def toJson = {
    val archiveList = Json.toJson(archives.map(a => Json.toJson(Map(
      "id" -> JsString(a.id),
      "date" -> JsString(a.date.toString()),
      "size" -> Json.toJson(a.size),
      "state" -> JsString(a.state.toString)))))
    Json.toJson(Map("type" -> JsString("snapshotList"),
      "data" -> Json.toJson(archiveList)))
  }
}

case class ProgressRequest(id: String) extends RequestEvent {
  def toJson = Json.toJson(Map("type" -> "progressRequest", "archive" -> id))
}
case class ProgressResponse(id: String, title: String, label: String, value: Int) extends ResponseEvent {
  def toJson = Json.toJson(Map(
      "type" -> JsString("progress"),
      "archive" -> JsString(id),
      "title" -> JsString(title),
      "label" -> JsString(label),
      "value" -> Json.toJson(value)))
}

case class DownloadRequest(id: String) extends RequestEvent {
  def toJson = Json.toJson(Map("type" -> "download", "archive" -> id))
}
*/
case object UnkownEvent extends RequestEvent

object EventJsonProtocol {
  val a = Serialization.formats(ShortTypeHints(List(
    classOf[GetArchiveList],
    classOf[ArchiveList],
    classOf[SnapshotList],
    classOf[GetSnapshotList],
    classOf[Subscribe],
    classOf[Unsubscribe],
    classOf[AddIgnoreSubscription],
    classOf[RemoveIgnoreSubscription],
    classOf[CreateSnapshot],
    classOf[UploadSnapshot],
    classOf[AddFile],
    classOf[SnapshotCreated],
    classOf[ArchiveCreation],
    classOf[RefreshInventory],
    classOf[Error],
    //classOf[AttributeChange[_]],
    //classOf[AttributeListChange[_]],
    classOf[GetSnapshotFiles],
    classOf[FileElement],
    classOf[UpdateSnapshotFileFilter])))
}
//    case None => // ERROR
//  }
//
//object Event {
//  val fromJson: Enumeratee[JsValue, RequestEvent] = Enumeratee.map[JsValue] { body =>
//    (body \ "type").as[String] match {
//      case "GetArchiveList" => GetArchiveList
//      case "GetSnapshotList" => GetSnapshotList
//      case "Subscribe" => Subscribe((body \ "data").as[String])
//      case "Unsubscribe" => Unsubscribe((body \ "data").as[String])
//      case "AddIgnoreSubscription" => AddIgnoreSubscription((body \ "data").as[String])
//      case "RemoveIgnoreSubscription" => RemoveIgnoreSubscription((body \ "data").as[String])
//      case "CreateSnapshot" => CreateSnapshot
//      case "AddFile" => AddFile(
//          (body \ "data" \ "snapshotId").as[String],
//          (body \ "data" \ "file").as[String])
//      case "GetSnapshotFiles" => GetSnapshotFiles(
//          (body \ "data" \ "snapshotId").as[String],
//          (body \ "data" \ "directory").as[String])
//      case "UpdateSnapshotFileFilter" => UpdateSnapshotFileFilter(
//          (body \ "data" \ "snapshotId").as[String],
//          (body \ "data" \ "directory").as[String],
//          (body \ "data" \ "filter").as[String])
//      case "UploadSnapshot" => UploadSnapshot(
//          (body \ "data" \ "snapshotId").as[String])
//      /*
//      case JsString("connect") => Connect
//      
//      case JsString("progressRequest") => ProgressRequest((body \ "data" \ "archive").as[String])
//      case JsString("download") =>
//        println(body)
//        DownloadRequest((body \ "data" \ "archive").as[String])
//        */
//      case _ =>
//        println("Received unknown event : %s".format(body))
//        UnkownEvent
//    }
//  }
//  val toJson: Enumeratee[ResponseEvent, JsValue] = Enumeratee.map[ResponseEvent] { _.json }
//}
//
//
