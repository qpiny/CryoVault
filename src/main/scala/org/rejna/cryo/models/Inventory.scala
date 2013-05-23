package org.rejna.cryo.models

import scala.io.Source
import scala.concurrent.duration._
import scala.collection.mutable.Buffer
import scala.language.postfixOps

import akka.actor.{ Actor, ActorRef, Props }
import akka.pattern.ask
import akka.util.Timeout

import java.io.FileOutputStream
import java.nio.file.{ Files, Path }
import java.nio.file.StandardOpenOption._
import java.nio.{ CharBuffer, ByteBuffer }
import java.nio.channels.FileChannel
import java.util.UUID

import org.joda.time.{ DateTime, Interval }

sealed abstract class InventoryRequest
sealed abstract class InventoryResponse
sealed class InventoryError(message: String, cause: Option[Throwable]) extends Exception(message, cause.get)

case object CreateArchive extends InventoryRequest
case class ArchiveCreated(id: String) extends InventoryResponse
case object CreateSnapshot extends InventoryRequest
case class SnapshotCreated(aref: ActorRef) extends InventoryResponse

case class InventoryMessage(data: String) // TODO

class Inventory(cryoctx: CryoContext) extends Actor with LoggingClass {
  implicit val contextExecutor = context.system.dispatcher
  implicit val timeout = Timeout(10 seconds)

  val attributeBuilder = new AttributeBuilder("/cryo/inventory")
  val inventoryDataId = "inventory"

  val dateAttribute = attributeBuilder("date", DateTime.now)
  def date = dateAttribute()
  private def date_= = dateAttribute() = _

  //val snapshots = attributeBuilder.map("snapshots", Map[String, Snapshot]())
  val archiveIds = Buffer.empty[String]

  override def preStart = {
    CryoEventBus.subscribe(self, "/cryo/datastore")
    cryoctx.datastore ! GetDataStatus(inventoryDataId)
  }

  def receive = {
    case DataStatus(id, status, size, checksum) =>
      if (id == inventoryDataId && status == EntryStatus.Created)
        cryoctx.datastore ! ReadData(inventoryDataId, 0, size.toInt)

    case DataRead(id, position, buffer) =>
      if (id == "inventory") {
        val message = InventoryMessage(buffer.asByteBuffer.asCharBuffer.toString)
        //      date = message.date
        //      val (s, a) = message.archives.partition(_.archiveType == Index)
        //      snapshots ++= s.map { a => a.id -> new RemoteSnapshot(a) }
        //      archives ++= a.map { a => a.id -> a }
      }
    case AttributeChange(path, attribute) =>
      path match {
        case AttributePath("datastore", inventoryDataId, "status") =>
          CryoEventBus.publish(AttributeChange("/cryo/inventory#status", attribute))
          if (attribute.now == EntryStatus.Created) {
            cryoctx.datastore ! GetDataStatus(inventoryDataId)
          }
        case AttributePath("datastore", id, attr) =>
          if (archiveIds.contains(id))
            CryoEventBus.publish(AttributeChange(s"/cryo/archives/${id}#${attr}", attribute))
        // idem for snapshots ?
      }
    case CreateArchive =>
      val requester = sender
      (cryoctx.datastore ? CreateData).map {
        case DataCreated(id) =>
          requester ! ArchiveCreated(id)
        case e: DataStoreError =>
          requester ! new InventoryError("Error while creating a new archive", Some(e))
      }

    case CreateSnapshot =>
      val requester = sender
      (cryoctx.datastore ? CreateData).map {
        case DataCreated(id) =>
          val aref = context.actorOf(Props[LocalSnapshot])
          // TODO watch aref
          requester ! SnapshotCreated(aref)
        case e: DataStoreError =>
          requester ! new InventoryError("Error while creating a new snapshot", Some(e))

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