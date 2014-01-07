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
import java.nio.file.StandardOpenOption._
import java.nio.{ CharBuffer, ByteBuffer }
import java.nio.channels.FileChannel
import java.util.{ Date, UUID }

case class InventoryMessage(date: Date, entries: List[DataStatus])
case class UpdateInventoryDate(date: Date)
case class AddArchive(id: UUID)
case class AddSnapshot(id: UUID)

import ObjectStatus._
import DataType._

class Inventory(_cryoctx: CryoContext) extends CryoActor(_cryoctx) {
  private var isDying = false

  val attributeBuilder = CryoAttributeBuilder("/cryo/inventory")

  val inventoryId = new UUID(0x0000000000001000L, 0xC47000000001L)
  val inventoryGlacierId = "inventory"

  val dateAttribute = attributeBuilder("date", new Date())
  def date = dateAttribute()
  def date_= = dateAttribute() = _

  val statusAttribute = attributeBuilder("status", Remote: ObjectStatus)
  def status = statusAttribute()
  def status_= = statusAttribute() = _

  val updateArchiveSubscription = new AttributeListCallback {
    def onListChange[A](name: String, addedValues: List[A], removedValues: List[A]) = {
      log.info(s"MetaAttribute(${name}).updateArchiveSubscription")
      addedValues foreach {
        case id: UUID => CryoEventBus.subscribe(self, s"/cryo/datastore/${id}")
      }
      removedValues foreach {
        case id: UUID => CryoEventBus.unsubscribe(self, s"/cryo/datastore/${id}")
      }
    }
  }

  def publishDataStatusChange(path: String) = new AttributeListCallback {
    def onListChange[A](name: String, addedValues: List[A], removedValues: List[A]) = {
      Future.sequence(addedValues.map {
        case id: UUID => cryoctx.datastore ? GetDataStatus(id)
      }) onComplete {
        case Success(addedDataStatus) =>
          log.debug(s"attribute[${path}] add: ${addedValues.take(10)} remove: ${removedValues.take(10)}")
          CryoEventBus.publish(AttributeListChange(path, addedDataStatus, removedValues))
        case e: Any => log(cryoError("Fail to publish inventory change", e))
      }
    }
  }
  //implicit val timeout = 10 seconds
  val snapshotIds = attributeBuilder.list("snapshotIds", List[UUID]())
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
  val snapshotActors = HashMap.empty[UUID, ActorRef]
  snapshotIds <+> updateArchiveSubscription
  snapshotIds <+> new AttributeListCallback {
    def onListChange[A](name: String, addedValues: List[A], removedValues: List[A]) = {
      val ids = removedValues.asInstanceOf[List[UUID]]
      for (
        id <- ids;
        aref <- snapshotActors.get(id)
      ) aref ! PoisonPill
    }
  }
  snapshotIds <+> publishDataStatusChange("/cryo/inventory#snapshots")

  val archiveIds = attributeBuilder.list("archiveIds", List[UUID]())
  //  val archives = attributeBuilder.futureList("archives", () => {
  //    Future.sequence(archiveIds.map {
  //      case aid => (cryoctx.datastore ? GetDataStatus(aid)).mapTo[DataStatus]
  //    } toList)
  //  })
  //  archives <* archiveIds
  archiveIds <+> updateArchiveSubscription
  archiveIds <+> publishDataStatusChange("/cryo/inventory#archives")

  CryoEventBus.subscribe(self, s"/cryo/datastore/${inventoryId}")

  override def preStart = reload

  private def getDataStatusList(ids: TraversableOnce[UUID]) = {
    Future.sequence(ids.map(id => (cryoctx.datastore ? GetDataStatus(id)).mapTo[DataStatus]).toList)
  }

  private def save(): Future[Unit] = {
    (cryoctx.datastore ? CreateData(Some(inventoryId), DataType.Internal))
      .eflatMap("Fail to create inventory", {
        case Created(id) =>
          getDataStatusList(archiveIds ++ snapshotIds)
            .flatMap {
              case archiveList =>
                val inventoryMsg = InventoryMessage(date, archiveList)
                cryoctx.datastore ? WriteData(id, ByteString(Json.write(inventoryMsg)))
            }
      }).eflatMap("Fail to write inventory", {
        case DataWritten(id, _, _) =>
          cryoctx.datastore ? PackData(id, inventoryGlacierId)
      }).emap("Fail to close inventory", {
        case Success(DataPacked(id, glacierId)) =>
          status = Readable
          log.info("Inventory saved")
      })
  }

  private def loadFromDataStore(size: Long): Future[ObjectStatus] = {
    import DataType._

    (cryoctx.datastore ? ReadData(inventoryId, 0, size.toInt))
      .emap("Fail to read inventory data", {
        case DataRead(id, position, buffer) =>
          val message = buffer.decodeString("UTF-8")
          val inventory = Json.read[InventoryMessage](message)
          cryoctx.inventory ! UpdateInventoryDate(inventory.date)
          inventory.entries.collect {
            case ds @ DataStatus(id, glacierId, dataType, creationDate, status, size, checksum) =>
              dataType match {
                case Data => cryoctx.inventory ! AddArchive(id)
                case Index => cryoctx.inventory ! AddSnapshot(id)
                case _ =>
              }
              if (glacierId.isDefined)
              status match {
                case Remote =>
                  (cryoctx.datastore ? DefineData(id, glacierId.get, dataType, creationDate, size, checksum))
                    .emap("Fail to define data for ${ds}", {
                      case DataDefined(id) => log.info(s"Remote data ${id} defined")
                    })
                case _ =>
              }

          }
          Readable
      })
  }

  private def reload() = {
    (cryoctx.datastore ? GetDataStatus(inventoryId))
      .eflatMap("Fail to get job list", {
        case DataStatus(_, _, _, _, Readable, size, _) =>
          loadFromDataStore(size)
        case DataStatus(_, _, _, _, status, _, _) =>
          log.info("Waiting for inventory download completion")
          Future(status)
        case DataNotFoundError(id, _, _) =>
          log.info("No inventory found in datastore")
          (cryoctx.notification ? GetNotification())
            .emap("Fail to get notification", {
              case Done() =>
            }).flatMap({
              case _ => (cryoctx.manager ? GetJobList())
            }).eflatMap("Fail to get job list", {
              case JobList(jl) if !jl.exists(_.objectId == "inventory") =>
                (cryoctx.cryo ? RefreshInventory())
                  .emap("Fail to refresh inventory", {
                    case JobRequested(job) =>
                      log.info(s"Inventory update requested (${job.id})")
                      Writable
                  })
              case _: JobList =>
                log.info("Inventory update has been already requested")
                Future(Writable)
            })
      }).onComplete({
        case Failure(t) =>
          log(cryoError("An error has occured while updating inventory", t))
        case Success(s) =>
          status = s
      })
  }

  private def createSnapshot: Future[UUID] = {
    (cryoctx.datastore ? CreateData(None, Index))
      .emap("Error while creating a new snapshot", {
        case Created(id) =>
          //val aref = context.actorOf(Props(classOf[SnapshotCreating], cryoctx, id))
          snapshotIds += id
          id
      })
  }

  def receive = cryoReceive {
    case PrepareToDie() if !isDying =>
      isDying = true
      Future.sequence(snapshotActors.values map { _ ? PrepareToDie() })
        .flatMap({
          case _ => save()
        }).map({
          case _ => ReadyToDie()
        }).recover({
          case e: Any =>
            log(cryoError("Inventory shutdown has generated an error", e))
            ReadyToDie()
        }).reply("", sender)

    ////// private messages //////////////
    case UpdateInventoryDate(d) => date = d
    case AddArchive(id) => archiveIds += id
    case AddSnapshot(id) => snapshotIds += id
    //////////////////////////////////////

    case AttributeChange(path, previous, now) =>
      if (!isDying) path match {
        case AttributePath("datastore", objectId, attr) if objectId == inventoryId.toString =>
          if (attr == "status")
            CryoEventBus.publish(AttributeChange("/cryo/inventory#status", previous, now))
          if (now == Readable) {
            reload
          }
        case AttributePath("datastore", _id, attr) =>
          val id = UUID.fromString(_id)
          if (archiveIds contains id)
            CryoEventBus.publish(AttributeChange(s"/cryo/archives/${id}#${attr}", previous, now))
          else if (snapshotIds contains id)
            CryoEventBus.publish(AttributeChange(s"/cryo/snapshots/${id}#${attr}", previous, now))
          else if (id != inventoryId)
            log.warn(s"Data ${id} has changed but it is not an archive nor a snapshot")
      }

    case CreateArchive() =>
      (cryoctx.datastore ? CreateData(None, Data))
        .emap("Fail to create data", {
          case Created(id) =>
            archiveIds += id
            Created(id)
        }).reply("Error while creating a new archive", sender)

    case DeleteArchive(id) =>
      archiveIds -= id
      (cryoctx.datastore ? DeleteData(id))
        .reply(s"Error while deleting archive ${id}", sender)

    case DeleteSnapshot(id) =>
      snapshotIds -= id
      (cryoctx.datastore ? DeleteData(id))
        .reply(s"Error while deleting snapshot ${id}", sender)

    case CreateSnapshot() =>
      createSnapshot.map(Created(_))
        .reply("Fail to create snapshot", sender)

    case GetArchiveList() =>
      getDataStatusList(archiveIds)
        .map(ObjectList(date, status, _))
        .reply("Can't get archive list", sender)

    case GetSnapshotList() =>
      getDataStatusList(snapshotIds)
        .map(ObjectList(date, status, _))
        .reply("Can't get snapshot list", sender)

    case sr: SnapshotMessage =>
      val id = sr.id
      if (snapshotIds.contains(id)) {
        snapshotActors.get(id) match {
          case Some(aref) => aref.forward(sr)
          case None =>
            val _sender = sender
            (cryoctx.datastore ? GetDataStatus(id)) onComplete {
              case Success(DataStatus(_, _, _, _, _ /* DEBUG Creating */ , _, _)) =>
                val aref = context.actorOf(Props(classOf[Snapshot], cryoctx, id))
                aref ! Writable
                snapshotActors += id -> aref
                aref.tell(sr, _sender)
              //              case Success(d: DataStatus) =>
              //                val aref = context.actorOf(Props(classOf[RemoteSnapshot], cryoctx, id))
              //                snapshotActors += id -> aref
              //                aref.tell(sr, _sender)
              case e: Any =>
                _sender ! cryoError(s"Fail to process snapshot request ${sr}", e)
            }
        }
      } else {
        sender ! NotFoundError(s"Snapshot ${sr.id} was not found")
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