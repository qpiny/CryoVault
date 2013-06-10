package org.rejna.cryo.models

import scala.io.Source
import scala.concurrent.Future
import scala.collection.mutable.ListBuffer
import scala.collection.JavaConversions._
import scala.util.{ Success, Failure }

import akka.actor.{ ActorRef, Props }
import akka.util.ByteString

import java.io.FileOutputStream
import java.nio.file.{ Files, Path }
import java.nio.file.StandardOpenOption._
import java.nio.{ CharBuffer, ByteBuffer }
import java.nio.channels.FileChannel
import java.util.Date
//import org.joda.time.{ Date, Interval }
import net.liftweb.json.Serialization
import net.liftweb.json.JsonDSL._

sealed abstract class InventoryInternalMessage
sealed abstract class InventoryRequest extends Request
sealed abstract class InventoryResponse extends Response
sealed class InventoryError(message: String, cause: Throwable) extends CryoError(message, cause)

case class UpdateInventoryDate(date: Date) extends InventoryInternalMessage
case class AddArchive(id: String) extends InventoryInternalMessage
case class AddSnapshot(id: String) extends InventoryInternalMessage
case class CreateArchive() extends InventoryRequest
case class ArchiveCreated(id: String) extends InventoryResponse
case class CreateSnapshot() extends InventoryRequest
case class SnapshotCreated(id: String, aref: ActorRef) extends InventoryResponse
case class GetArchiveList() extends InventoryRequest
case class ArchiveIdList(archiveIds: List[String]) extends InventoryResponse
case class GetSnapshotList() extends InventoryRequest
case class SnapshotIdList(snapshots: Map[String, ActorRef]) extends InventoryResponse
case class SnapshotNotFound(id: String, message: String, cause: Throwable = null) extends InventoryError(message, cause)

case class InventoryEntry(id: String, description: String, creationDate: Date, size: Long, checksum: String)
case class InventoryMessage(date: Date, entries: List[InventoryEntry])

class Inventory(val cryoctx: CryoContext) extends CryoActor {
  val attributeBuilder = AttributeBuilder("/cryo/inventory")
  val inventoryDataId = "inventory"

  val dateAttribute = attributeBuilder("date", new Date())
  def date = dateAttribute()
  private def date_= = dateAttribute() = _

  val snapshots = attributeBuilder.map("snapshots", Map[String, ActorRef]())
  val archiveIds = attributeBuilder.list("archives", List[String]())

  override def preStart = {
    CryoEventBus.subscribe(self, s"/cryo/datastore/${inventoryDataId}")
    reloadInventory
  }

  override def postStop = {

  }

  def saveInventory = {
    implicit val format = Json

    cryoctx.datastore ? CreateData(Some(inventoryDataId), "Inventory") flatMap {
      case DataCreated(id) =>
        Future.sequence((snapshots.keys ++ archiveIds) map {
          case aid => cryoctx.datastore ? GetDataStatus(aid) map {
            case ds: DataStatus =>
              ("ArchiveId" -> ds.id) ~
                ("ArchiveDescription" -> ds.description) ~
                ("CreationDate" -> Json.dateFormat.format(ds.creationDate)) ~
                ("Size" -> ds.size) ~
                ("SHA256TreeHash" -> ds.checksum)
            case e: Any => throw CryoError("Fail to get data status", e)
          }
        }) flatMap {
          case archiveList =>
            cryoctx.datastore ? WriteData(id, ByteString(Serialization.write(
              //("VaultARN" -> ??) ~
              ("InventoryDate" -> Json.dateFormat.format(date)) ~
                ("ArchiveList" -> archiveList))))
        }
      case e: Any => throw CryoError("Fail to create inventory", e)
    } flatMap {
      case DataWritten(id, _, _) =>
        cryoctx.datastore ? CloseData(id)
      case e: Any =>
        throw CryoError("Fail to write inventory", e)
    } onComplete {
      case Success(DataClosed(id)) => log.info("Inventory saved")
      case o: Any => log.error(CryoError("Fail to save inventory", o))
    }
  }
  
  def loadInventory(size: Long) = {
    implicit val formats = Json
    cryoctx.datastore ? ReadData(inventoryDataId, 0, size.toInt) map {
      case DataRead(id, position, buffer) =>
        val message = buffer.decodeString("UTF-8")
        val inventory = Serialization.read[InventoryMessage](message)
        cryoctx.inventory ! UpdateInventoryDate(inventory.date)

        for (entry <- inventory.entries) {
          (cryoctx.datastore ? DefineData(entry.id, entry.description, entry.creationDate, entry.size, entry.checksum)) map {
            case DataDefined(_) =>
              if (entry.description.startsWith("Data"))
                cryoctx.inventory ! AddArchive(entry.id)
              else
                cryoctx.inventory ! AddSnapshot(entry.id)
            case o =>
              throw CryoError(s"Fail to define archive data ${entry.id}", o)
          }
        }
        log.info("Inventory updated")
      case o: Any => throw CryoError("Fail to read inventory data", o)
    }
  }

  def reloadInventory = {
    (cryoctx.datastore ? GetDataStatus(inventoryDataId)) flatMap {
      case DataStatus(_, _, _, status, size, _) if status == EntryStatus.Created => loadInventory(size)
      case _: DataStatus => Future(log.info("Waiting for inventory download completion"))
      case DataNotFoundError(id, _, _) =>
        (cryoctx.manager ? GetJobList()) flatMap {
          case JobList(jl) if !jl.exists(_.isInstanceOf[InventoryJob]) =>
            (cryoctx.cryo ? RefreshInventory()) map {
              case RefreshInventoryRequested(job) => log.info(s"Inventory update requested (${job.id})")
              case o: Any => throw CryoError("Fail to refresh inventory", o)
            }
          case _: JobList =>
            Future(log.info("Inventory update has been already requested"))
          case o: Any =>
            throw CryoError("Fail to get job list", o)
        }
      case o: Any => throw CryoError("Fail to get inventory status", o)
    } onFailure {
      case t => log.error(CryoError("An error has occured while updating inventory", t))
    }

  }

  def cryoReceive = {
    case UpdateInventoryDate(d) =>
      date = d

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
            reloadInventory
          }
        case AttributePath("datastore", id, attr) =>
          if (archiveIds.contains(id))
            CryoEventBus.publish(AttributeChange(s"/cryo/archives/${id}#${attr}", attribute))
        // idem for snapshots ?
      }
    case CreateArchive() =>
      val requester = sender
      (cryoctx.datastore ? CreateData).onComplete {
        case Success(DataCreated(id)) =>
          archiveIds += id
          requester ! ArchiveCreated(id)
        case e: Any =>
          requester ! CryoError("Error while creating a new archive", e)
      }

    case CreateSnapshot() =>
      val requester = sender
      (cryoctx.datastore ? CreateData).onComplete {
        case Success(DataCreated(id)) =>
          val aref = context.actorOf(Props[LocalSnapshot])
          snapshots += id -> aref
          // TODO watch aref
          requester ! SnapshotCreated(id, aref)
        case e: Any =>
          requester ! CryoError("Error while creating a new snapshot", e)
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