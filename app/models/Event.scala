package models

import java.util.Date
import java.io.File

import play.api.libs.iteratee._
import play.api.libs.json._

import akka.event.{ ActorEventBus, LookupClassification }
import akka.util.Duration
import akka.util.duration._

import com.amazonaws.services.glacier.model.DescribeJobResult

import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

import CryoJson._

object Event {
  val fromJson: Enumeratee[JsValue, RequestEvent] = Enumeratee.map[JsValue] { body =>
    (body \ "type").as[String] match {
      case "GetArchiveList" => GetArchiveList
      case "GetSnapshotList" => GetSnapshotList
      case "Subscribe" => Subscribe((body \ "data").as[String])
      case "Unsubscribe" => Unsubscribe((body \ "data").as[String])
      case "AddIgnoreSubscription" => AddIgnoreSubscription((body \ "data").as[String])
      case "RemoveIgnoreSubscription" => RemoveIgnoreSubscription((body \ "data").as[String])
      case "CreateSnapshot" => CreateSnapshot
      case "AddFile" => AddFile(
          (body \ "data" \ "snapshotId").as[String],
          (body \ "data" \ "file").as[String])
      case "GetSnapshotFiles" => GetSnapshotFiles(
          (body \ "data" \ "snapshotId").as[String],
          (body \ "data" \ "directory").as[String])
      case "UpdateSnapshotFileFilter" => UpdateSnapshotFileFilter(
          (body \ "data" \ "snapshotId").as[String],
          (body \ "data" \ "directory").as[String],
          (body \ "data" \ "filter").as[String])
      case "UploadSnapshot" => UploadSnapshot(
          (body \ "data" \ "snapshotId").as[String])
      /*
      case JsString("connect") => Connect
      
      case JsString("progressRequest") => ProgressRequest((body \ "data" \ "archive").as[String])
      case JsString("download") =>
        println(body)
        DownloadRequest((body \ "data" \ "archive").as[String])
        */
      case _ =>
        println("Received unknown event : %s".format(body))
        UnkownEvent
    }
  }
  val toJson: Enumeratee[ResponseEvent, JsValue] = Enumeratee.map[ResponseEvent] { _.json }
}

abstract class Event
abstract class RequestEvent extends Event {
  var responseChannel: Option[PushEnumerator[ResponseEvent]] = None
  def withChannel(c: PushEnumerator[ResponseEvent]): RequestEvent = {
    responseChannel = Some(c)
    this
  }
}
abstract class ResponseEvent(val path: String, val json: JsValue) extends Event

case object GetArchiveList extends RequestEvent
case class ArchiveList(archives: List[Archive]) extends ResponseEvent("<null>", JsObject(Seq(
    "type" -> JsString("ArchiveList"),
    "data" -> JsArray(archives.map(a => ArchiveFormat(a)))
    )))
case class SnapshotList(snapshots: List[Snapshot]) extends ResponseEvent("<null>", JsObject(Seq(
    "type" -> JsString("SnapshotList"),
    "data" -> JsArray(snapshots.map(a => SnapshotFormat(a)))
    )))

case object GetSnapshotList extends RequestEvent
case class Subscribe(subscription: String) extends RequestEvent
case class Unsubscribe(subscription: String) extends RequestEvent
case class AddIgnoreSubscription(subscription: String) extends RequestEvent
case class RemoveIgnoreSubscription(subscription: String) extends RequestEvent
case object CreateSnapshot extends RequestEvent
case class UploadSnapshot(snapshotId: String) extends RequestEvent
case class AddFile(snapshotId: String, file: String) extends RequestEvent
case class SnapshotCreated(id: String) extends ResponseEvent("<null>", JsObject(Seq(
    "type" -> JsString("SnapshotCreated"),
    "data" -> JsString(id)
    )))
case class ArchiveCreation(file: File, archiveType: ArchiveType.ArchiveType, id: String, state: CryoStatus.CryoStatus) extends ResponseEvent(id, JsString("plop"))
case class UpdateInventory(maxAge: Duration = 24 hours)

case class Error(message: String) extends ResponseEvent("<null>", JsObject(Seq(
    "type" -> JsString("SnapshotList"),
    "data" -> JsString(message)
    )))
class AttributeChange[A](path: String, attribute: ReadAttribute[A, JsValue])(implicit serializer: A => JsValue) extends ResponseEvent(path, JsObject(Seq(
    "type" -> JsString("AttributeChange"),
    "path" -> JsString(path),
    "before" -> serializer(attribute.previous),
    "after" -> serializer(attribute.now))))
class AttributeListChange[A](path: String, addedValues: List[A], removedValues: List[A])(implicit serializer: List[A] => JsValue) extends ResponseEvent(path, JsObject(Seq(
    "type" -> JsString("AttributeListChange"),
    "path" -> JsString(path),
    "addedValues" -> serializer(addedValues),
    "removedValues" -> serializer(removedValues))))
case class GetSnapshotFiles(snapshotId: String, directory: String) extends RequestEvent
class FileElement(file: File, count: Int, size: Long, filter: Option[String]) {
  def toJson = JsObject(Seq(
      "name" -> JsString(file.getName),
      "type" -> JsString(if (file.isDirectory) "directory" else "file"),
      "count" -> JsNumber(count),
      "size" -> JsNumber(size),
      "filter" -> filter.map(JsString).getOrElse(JsNull)
      ))
}
class SnapshotFiles(snapshotId: String, directory: String, fileElements: List[FileElement]) extends ResponseEvent("<null>", JsObject(Seq(
    "type" -> JsString("SnapshotFiles"),
    "directory" -> JsString(directory),
    "files" -> JsArray(fileElements.map(_.toJson))
    )))
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