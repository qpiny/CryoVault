package org.rejna.cryo.web

import java.nio.file.Path
import java.util.Date

import scala.concurrent.duration._

import akka.actor.ActorRef

import org.json4s._
import org.json4s.native.Serialization

import org.rejna.cryo.models._

//case class GetArchiveList() extends RequestEvent
//case class ArchiveList(archives: List[String]) extends ResponseEvent("<null>")
//case class SnapshotList(snapshots: List[String]) extends ResponseEvent("<null>")
//
//case class GetSnapshotList() extends RequestEvent
case class Subscribe(subscription: String) extends Request
case class Unsubscribe(subscription: String) extends Request
case class AddIgnoreSubscription(subscription: String) extends Request
case class RemoveIgnoreSubscription(subscription: String) extends Request

object JsonWithTypeHints extends Formats {
  implicit val format = this

  override val typeHints = ShortTypeHints(
    //classOf[Exit] ::
      classOf[Subscribe] ::
      classOf[Unsubscribe] ::
      classOf[AttributeChange[_]] ::
      classOf[AttributeListChange[_]] ::
      classOf[DataStatus] ::
      classOf[DataEntry] ::
      Nil)

  val dateFormat = Json.dateFormat
  override val typeHintFieldName = "type"
  override val customSerializers = Json.customSerializers

  def read[T](json: String)(implicit mf: Manifest[T]): T = {
    Serialization.read[T](json)
  }

  def write[T <: AnyRef](t: T): String = {
    Serialization.write(t)
  }
}


private object JsonActorRef extends CustomSerializer[ActorRef](format => (PartialFunction.empty,
    {
      case ar: ActorRef => JString(ar.toString)
    }))
//case class Archive(id: String, creationDate: DateTime, )
//case class CreateSnapshot() extends RequestEvent
//case class UploadSnapshot(snapshotId: String) extends RequestEvent
//case class AddFile(snapshotId: String, file: String) extends RequestEvent
//case class SnapshotCreated(id: String) extends ResponseEvent("<null>")
////case class ArchiveCreation(file: Path, archiveType: ArchiveType.ArchiveType, id: String, state: CryoStatus.CryoStatus) extends ResponseEvent(id)
//case class RefreshInventory(maxAge: Duration = 24 hours) extends RequestEvent
//
//case class GetSnapshotFiles(snapshotId: String, directory: String) extends Request
//case class FileElement(file: Path, count: Int, size: Long, filter: Option[FileFilter])
//case class SnapshotFiles(snapshotId: String, directory: String, files: Iterable[FileElement]) extends ResponseEvent("<null>")
//case class UpdateSnapshotFileFilter(snapshotId: String, directory: String, filter: String) extends RequestEvent
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
