package org.rejna.cryo.models

import scala.io.Source
import scala.concurrent.Future
import scala.collection.mutable.ListBuffer
import scala.collection.JavaConversions._

import akka.actor.{ ActorRef, Props }

import java.io.FileOutputStream
import java.nio.file.{ Files, Path }
import java.nio.file.StandardOpenOption._
import java.nio.{ CharBuffer, ByteBuffer }
import java.nio.channels.FileChannel
import java.util.UUID
import org.joda.time.{ DateTime, Interval }
import net.liftweb.json.Serialization

sealed abstract class InventoryRequest extends Request
sealed abstract class InventoryResponse extends Response
sealed class InventoryError(message: String, cause: Throwable) extends CryoError(message, cause)

case class AddArchive(id: String)
case class AddSnapshot(id: String)
case class CreateArchive() extends InventoryRequest
case class ArchiveCreated(id: String) extends InventoryResponse
case class CreateSnapshot() extends InventoryRequest
case class SnapshotCreated(id: String, aref: ActorRef) extends InventoryResponse
case class GetArchiveList() extends InventoryRequest
case class ArchiveIdList(archiveIds: List[String]) extends InventoryResponse
case class GetSnapshotList() extends InventoryRequest
case class SnapshotIdList(snapshots: Map[String, ActorRef]) extends InventoryResponse
case class SnapshotNotFound(id: String, message: String, cause: Throwable = null) extends InventoryError(message, cause)

case class InventoryEntry(id: String, description: String, creationDate: DateTime, size: Long, checksum: String)
case class InventoryMessage(date: DateTime, entries: List[InventoryEntry])

class Inventory(val cryoctx: CryoContext) extends CryoActor {
  val attributeBuilder = AttributeBuilder("/cryo/inventory")
  val inventoryDataId = "inventory"

  val dateAttribute = attributeBuilder("date", DateTime.now)
  def date = dateAttribute()
  private def date_= = dateAttribute() = _

  val snapshots = attributeBuilder.map("snapshots", Map[String, ActorRef]())
  val archiveIds = attributeBuilder.list("archives", List[String]())

  override def preStart = {
    CryoEventBus.subscribe(self, s"/cryo/datastore/${inventoryDataId}")
    cryoctx.datastore ! GetDataStatus(inventoryDataId)
  }

  def cryoReceive = {
    case DataStatus(id, description, creationDate, status, size, checksum) =>
      if (id == inventoryDataId && status == EntryStatus.Created)
        cryoctx.datastore ! ReadData(inventoryDataId, 0, size.toInt)

    case DataNotFoundError(id, _, _) =>
      if (id == inventoryDataId) {
        (cryoctx.manager ? GetJobList()) map {
          case JobList(jl) =>
            if (!jl.exists(_.isInstanceOf[InventoryJob]))
              cryoctx.cryo ! RefreshInventory()
        }
      }
    case DataRead(id, position, buffer) =>
      if (id == "inventory") {
        implicit val formats = JsonSerialization.format
        val message = buffer.decodeString("UTF-8")
        log.debug(s"read buffer = ${message}")
        val inventory = Serialization.read[InventoryMessage](message)
        date = inventory.date

        for (entry <- inventory.entries) {
          (cryoctx.datastore ? DefineData(entry.id, entry.description, entry.creationDate, entry.size, entry.checksum)) map {
            case DataDefined(_) =>
              if (entry.description.startsWith("Data"))
                cryoctx.inventory ! AddArchive(entry.id)
              else
                cryoctx.inventory ! AddSnapshot(entry.id)
          }
        }
      }

    case AddArchive(id) =>
      archiveIds += id

    case AddSnapshot(id) =>
      if (!snapshots.contains(id))
        snapshots += id -> context.actorOf(Props(classOf[RemoteSnapshot], cryoctx, id))

    case AttributeChange(path, attribute) =>
      path match {
        case AttributePath("datastore", `inventoryDataId`, "status") =>
          CryoEventBus.publish(AttributeChange("/cryo/inventory#status", attribute))
          if (attribute.now == EntryStatus.Created) {
            cryoctx.datastore ! GetDataStatus(inventoryDataId)
          }
        case AttributePath("datastore", id, attr) =>
          if (archiveIds.contains(id))
            CryoEventBus.publish(AttributeChange(s"/cryo/archives/${id}#${attr}", attribute))
        // idem for snapshots ?
      }
    case CreateArchive() =>
      val requester = sender
      (cryoctx.datastore ? CreateData).map {
        case DataCreated(id) =>
          archiveIds += id
          requester ! ArchiveCreated(id)
        case e: DatastoreError =>
          requester ! new InventoryError("Error while creating a new archive", e)
      }

    case CreateSnapshot() =>
      val requester = sender
      (cryoctx.datastore ? CreateData).map {
        case DataCreated(id) =>
          val aref = context.actorOf(Props[LocalSnapshot])
          snapshots += id -> aref
          // TODO watch aref
          requester ! SnapshotCreated(id, aref)
        case e: DatastoreError =>
          requester ! new InventoryError("Error while creating a new snapshot", e)
      }
    case GetArchiveList() =>
      sender ! ArchiveIdList(archiveIds.toList)

    case GetSnapshotList() =>
      sender ! SnapshotIdList(snapshots.toMap)

    case sr: SnapshotRequest =>
      snapshots.get(sr.id) match {
        case None => sender ! SnapshotNotFound(sr.id, s"Snapshot ${sr.id} was not found")
        case Some(aref) => aref.forward(sr)
      }
    //    case MigrateArchive(archive, newId, size, hash) =>
    //      // TODO create an actor which manage store (all archive files)
    //      Files.move(archive.file, Config.getFile(archive.archiveType, newId))
    //      val r = new RemoteArchive(archive.archiveType, archive.date, newId, size, hash)
    //      archives -= archive.id
    //      archives += newId -> r
    //
    //    case MigrateSnapshot(snapshot, archive) =>
    //      var r = new RemoteSnapshot(archive.date, archive.id, archive.size, archive.hash)
    //      snapshots -= snapshot.id
    //      snapshots += r.id -> r
  }
}