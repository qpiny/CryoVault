package org.rejna.cryo.models

import scala.io.Source
import scala.concurrent.Future
import scala.collection.mutable.HashMap
import scala.collection.JavaConversions._
import scala.util.{ Success, Failure }
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{ ActorRef, Props, PoisonPill }
import akka.util.ByteString
import akka.event.Logging.Error

import java.io.FileOutputStream
import java.nio.file.{ Files, Path }
import java.nio.file.StandardOpenOption._
import java.nio.{ CharBuffer, ByteBuffer }
import java.nio.channels.FileChannel
import java.util.Date

//object InventoryStatus extends Enumeration {
//  type InventoryStatus = Value
//  val Unknown, Refreshing, Cached, Error = Value
//}

//import InventoryStatus._
import EntryStatus._

sealed abstract class InventoryInternalMessage
sealed abstract class InventoryRequest extends Request
sealed abstract class InventoryResponse extends Response
sealed abstract class InventoryError(message: String, cause: Throwable) extends GenericError {
  val source = classOf[Inventory].getName
  val marker = Markers.errMsgMarker
}

case class UpdateInventoryDate(date: Date) extends InventoryInternalMessage
case class AddArchive(id: String) extends InventoryInternalMessage
case class AddSnapshot(id: String) extends InventoryInternalMessage
case class CreateArchive() extends InventoryRequest
case class ArchiveCreated(id: String) extends InventoryResponse
case class CreateSnapshot() extends InventoryRequest
case class SnapshotCreated(id: String) extends InventoryResponse
case class DeleteSnapshot(id: String) extends InventoryRequest
case class SnapshotDeleted(id: String) extends InventoryResponse
case class DeleteArchive(id: String) extends InventoryRequest
case class ArchiveDeleted(id: String) extends InventoryResponse
case class GetArchiveList() extends InventoryRequest
case class ArchiveList(date: Date, status: EntryStatus, archives: List[DataStatus]) extends InventoryResponse

case class GetSnapshotList() extends InventoryRequest
case class SnapshotList(date: Date, status: EntryStatus, snapshots: List[DataStatus]) extends InventoryResponse
case class ArchiveNotFound(id: String, message: String, cause: Throwable = Error.NoCause) extends InventoryError(message, cause)
case class SnapshotNotFound(id: String, message: String, cause: Throwable = Error.NoCause) extends InventoryError(message, cause)

//case class DataStatus(id: String, description: String, creationDate: Date, status: EntryStatus.EntryStatus, size: Long, checksum: String) extends DatastoreResponse
//case class InventoryEntry(id: String, description: String, creationDate: Date, size: Long, checksum: String)
case class InventoryMessage(date: Date, entries: List[DataStatus])

class Inventory(_cryoctx: CryoContext) extends CryoActor(_cryoctx) {
  private var isDying = false

  val attributeBuilder = CryoAttributeBuilder("/cryo/inventory")
  val inventoryDataId = "inventory"

  val dateAttribute = attributeBuilder("date", new Date())
  def date = dateAttribute()
  def date_= = dateAttribute() = _

  val statusAttribute = attributeBuilder("status", Unknown)
  def status = statusAttribute()
  def status_= = statusAttribute() = _

  val updateArchiveSubscription = new AttributeListCallback {
    def onListChange[A](name: String, addedValues: List[A], removedValues: List[A]) = {
      log.info(s"MetaAttribute(${name}).updateArchiveSubscription")
      addedValues foreach {
        case id: String => CryoEventBus.subscribe(self, s"/cryo/datastore/${id}")
      }
      removedValues foreach {
        case id: String => CryoEventBus.unsubscribe(self, s"/cryo/datastore/${id}")
      }
    }
  }

  def publishDataStatusChange(path: String) = new AttributeListCallback {
    def onListChange[A](name: String, addedValues: List[A], removedValues: List[A]) = {
      Future.sequence(addedValues.map {
        case id: String => cryoctx.datastore ? GetDataStatus(id)
      }) onComplete {
        case Success(addedDataStatus) =>
          log.debug(s"attribute[${path}] add: ${addedValues.take(10)} remove: ${removedValues.take(10)}")
          CryoEventBus.publish(AttributeListChange(path, addedDataStatus, removedValues))
        case e: Any => log(CryoError("Fail to publish inventory change", e))
      }
    }
  }
  //implicit val timeout = 10 seconds
  val snapshotIds = attributeBuilder.list("snapshotIds", List[String]())
  //  val snapshots = attributeBuilder.futureList("snapshots", () => {
  //    Future.sequence(snapshotIds.keys.map {
  //      case sid => (cryoctx.datastore ? GetDataStatus(sid)).mapTo[DataStatus]
  //    } toList)
  //  }) // FIXME kill actor if snapshot is removed
  //  //snapshots <* snapshotIds
  //  snapshotIds <+> new AttributeListCallback {
  //    override def onListChange[A](name: String, addedValues: List[A], removedValues: List[A]) = {
  //      val add = addedValues.asInstanceOf[List[]]
  //    }
  //  }
  val snapshotActors = HashMap.empty[String, ActorRef]
  snapshotIds <+> updateArchiveSubscription
  snapshotIds <+> new AttributeListCallback {
    def onListChange[A](name: String, addedValues: List[A], removedValues: List[A]) = {
      val ids = removedValues.asInstanceOf[List[String]]
      for (
        id <- ids;
        aref <- snapshotActors.get(id)
      ) aref ! PoisonPill
    }
  }
  snapshotIds <+> publishDataStatusChange("/cryo/inventory#snapshots")

  val archiveIds = attributeBuilder.list("archiveIds", List[String]())
  //  val archives = attributeBuilder.futureList("archives", () => {
  //    Future.sequence(archiveIds.map {
  //      case aid => (cryoctx.datastore ? GetDataStatus(aid)).mapTo[DataStatus]
  //    } toList)
  //  })
  //  archives <* archiveIds
  archiveIds <+> updateArchiveSubscription
  archiveIds <+> publishDataStatusChange("/cryo/inventory#archives")

  CryoEventBus.subscribe(self, s"/cryo/datastore/${inventoryDataId}")

  override def preStart = {
    reload
  }

  private def getDataStatusList(ids: TraversableOnce[String]) = {
    Future.sequence(ids.map(id => (cryoctx.datastore ? GetDataStatus(id)).mapTo[DataStatus]).toList)
  }

  private def save() = {
    cryoctx.datastore ? CreateData(Some(inventoryDataId), "Inventory") flatMap {
      case DataCreated(id) =>
        val fArchList = getDataStatusList(archiveIds ++ snapshotIds)

        fArchList flatMap {
          case archiveList =>
            val inventoryMsg = InventoryMessage(date, archiveList)
            cryoctx.datastore ? WriteData(id, ByteString(Json.write(inventoryMsg)))
        }
      case e: Any => throw CryoError("Fail to create inventory", e)
    } flatMap {
      case DataWritten(id, _, _) =>
        cryoctx.datastore ? CloseData(id)
      case e: Any =>
        throw CryoError("Fail to write inventory", e)
    } map {
      case DataClosed(id) =>
        status = Cached
        log.info("Inventory saved")
      case o: Any =>
        throw CryoError("Fail to close inventory", o)
    }
  }

  private def loadFromDataStore(size: Long): Future[EntryStatus] = {
    cryoctx.datastore ? ReadData(inventoryDataId, 0, size.toInt) map {
      case DataRead(id, position, buffer) =>
        val message = buffer.decodeString("UTF-8")
        val inventory = Json.read[InventoryMessage](message)
        cryoctx.inventory ! UpdateInventoryDate(inventory.date)

        for (entry <- inventory.entries) {
          (cryoctx.datastore ? DefineData(entry.id, entry.description, entry.creationDate, entry.size, entry.checksum)) map {
            case DataDefined(_) =>
              if (entry.description.startsWith("Data")) // TODO make description more structured
                cryoctx.inventory ! AddArchive(entry.id)
              else
                cryoctx.inventory ! AddSnapshot(entry.id)
            case o =>
              throw CryoError(s"Fail to define archive data ${entry.id}", o)
          }
        }
        Cached
      case o: Any => throw CryoError("Fail to read inventory data", o)
    }
  }

  private def reload() = {
    (cryoctx.datastore ? GetDataStatus(inventoryDataId)) flatMap {
      case DataStatus(_, _, _, ds, size, _) if ds == Cached =>
        loadFromDataStore(size)
      case _: DataStatus =>
        log.info("Waiting for inventory download completion")
        Future(Downloading)
      case DataNotFoundError(id, _, _) =>
        log.info("No inventory found in datastore")
        (cryoctx.notification ? GetNotification()) map {
          case NotificationGot() =>
          case e: Any => log(CryoError("Fail to get notification", e))
        } flatMap { _ =>
          (cryoctx.manager ? GetJobList())
        } flatMap {
          case JobList(jl) if !jl.exists(_.objectId == "inventory") =>
            (cryoctx.cryo ? RefreshInventory()) map {
              case RefreshInventoryRequested(job) =>
                log.info(s"Inventory update requested (${job.id})")
                Downloading
              case o: Any => throw CryoError("Fail to refresh inventory", o)
            }
          case _: JobList =>
            log.info("Inventory update has been already requested")
            Future(Downloading)
          case o: Any =>
            throw CryoError("Fail to get job list", o)
        }
      case o: Any =>
        throw CryoError("Fail to get inventory status", o)
    } onComplete {
      case Failure(t) =>
        log(CryoError("An error has occured while updating inventory", t))
      case Success(s) =>
        status = s
    }
  }

  private def createSnapshot = {
    cryoctx.datastore ? CreateData(None, "Index") map {
      case DataCreated(id) =>
        val aref = context.actorOf(Props(classOf[SnapshotBuilder], cryoctx, id))
        snapshotIds += id
        id
      case e: Any =>
        throw CryoError("Error while creating a new snapshot", e)
    }
  }

  def receive = cryoReceive {
    case PrepareToDie() if !isDying =>
      isDying = true
      val _sender = sender
      Future.sequence(snapshotActors.values map { _ ? PrepareToDie() }) flatMap {
        case _ => save()
      } onComplete {
        case Success(_) =>
          log.debug("Inventory is ready to die")
          _sender ! ReadyToDie()
        case Failure(e) =>
          log.error("Inventory shutdown has generated an error", e)
          _sender ! ReadyToDie()
      }

    ////// private messages //////////////
    case UpdateInventoryDate(d) => date = d
    case AddArchive(id) => archiveIds += id
    case AddSnapshot(id) => snapshotIds += id
    //////////////////////////////////////

    case AttributeChange(path, previous, now) =>
      if (!isDying) path match {
        case AttributePath("datastore", `inventoryDataId`, attr) =>
          if (attr == "status")
            CryoEventBus.publish(AttributeChange("/cryo/inventory#status", previous, now))
          if (now == Cached) {
            reload
          }
        case AttributePath("datastore", id, attr) =>
          if (archiveIds contains id)
            CryoEventBus.publish(AttributeChange(s"/cryo/archives/${id}#${attr}", previous, now))
          else if (snapshotIds contains id)
            CryoEventBus.publish(AttributeChange(s"/cryo/snapshots/${id}#${attr}", previous, now))
          else if (id != inventoryDataId)
            log.warn(s"Data ${id} has changed but it is not an archive nor a snapshot")
      }

    case CreateArchive() =>
      val _sender = sender
      (cryoctx.datastore ? CreateData(None, "Data")).onComplete { // TODO set better description
        case Success(DataCreated(id)) =>
          archiveIds += id
          _sender ! ArchiveCreated(id)
        case e: Any =>
          _sender ! CryoError("Error while creating a new archive", e)
      }

    case DeleteArchive(id) =>
      val _sender = sender
      archiveIds -= id
      (cryoctx.datastore ? DeleteData(id)).onComplete {
        case Success(DataDeleted(id)) =>
          _sender ! ArchiveDeleted(id)
        case Success(DataNotFoundError(id, str, _)) =>
          _sender ! ArchiveNotFound(id, s"Archive ${id} was not found")
        case e: Any =>
          _sender ! CryoError(s"Error while deleting archive ${id}", e)
      }

    case DeleteSnapshot(id) =>
      val _sender = sender
      snapshotIds -= id
      (cryoctx.datastore ? DeleteData(id)).onComplete {
        case Success(DataDeleted(id)) =>
          _sender ! SnapshotDeleted(id)
        case Success(DataNotFoundError(id, str, _)) =>
          _sender ! SnapshotNotFound(id, s"Snapshot ${id} was not found")
        case e: Any =>
          _sender ! CryoError(s"Error while deleting snapshot ${id}", e)
      }

    case CreateSnapshot() =>
      val _sender = sender
      createSnapshot onComplete {
        case Success(id) =>
          _sender ! SnapshotCreated(id)
        case Failure(e) =>
          _sender ! CryoError("Fail to create snapshot", e)
      }

    case GetArchiveList() =>
      val _sender = sender
      getDataStatusList(archiveIds) onComplete {
        case Success(al) => _sender ! ArchiveList(date, status, al)
        case e: Any => _sender ! CryoError("Can't get archive list", e)
      }

    case GetSnapshotList() =>
      val _sender = sender
      getDataStatusList(snapshotIds) onComplete {
        case Success(sl) => _sender ! SnapshotList(date, status, sl)
        case e: Any => _sender ! CryoError("Can't get snapshot list", e)
      }

    case sr: SnapshotRequest =>
      val id = sr.id
      if (snapshotIds.contains(id)) {
        snapshotActors.get(id) match {
          case Some(aref) => aref.forward(sr)
          case None =>
            val _sender = sender
            (cryoctx.datastore ? GetDataStatus(id)) onComplete {
              case Success(DataStatus(_, _, _, Creating, _, _)) =>
                val aref = context.actorOf(Props(classOf[SnapshotBuilder], cryoctx, id))
                snapshotActors += id -> aref
                aref.tell(sr, _sender)
              case Success(d: DataStatus) =>
                val aref = context.actorOf(Props(classOf[RemoteSnapshot], cryoctx, id))
                snapshotActors += id -> aref
                aref.tell(sr, _sender)
              case e: Any => 
                _sender ! CryoError(s"Fail to process snapshot request ${sr}", e) 
            }
        }
      } else {
        sender ! SnapshotNotFound(id, s"Snapshot ${sr.id} was not found")
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