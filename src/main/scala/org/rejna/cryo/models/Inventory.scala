package org.rejna.cryo.models

import scala.io.Source
import scala.concurrent.duration._
import scala.collection.mutable.Buffer
import scala.language.postfixOps

import akka.actor.Actor
import akka.pattern.ask
import akka.util.Timeout

import java.io.FileOutputStream
import java.nio.file.{ Files, Path }
import java.nio.file.StandardOpenOption._
import java.nio.{ CharBuffer, ByteBuffer }
import java.nio.channels.FileChannel
import java.util.UUID

import org.joda.time.{ DateTime, Interval }

case class LoadInventoryFromFile(file: Path)
case class LoadInventoryFromMessage(message: InventoryMessage)
case object CreateArchive
case class ArchiveCreated(id: String)

case class InventoryMessage(data: String) // TODO

class Inventory extends Actor with LoggingClass {
  val glacier = context.actorFor("/user/glacier")
  val datastore = context.actorFor("/user/datastore")

  val attributeBuilder = new AttributeBuilder("/cryo/inventory")
  val dateAttribute = attributeBuilder("date", DateTime.now)
  def date = dateAttribute()
  private def date_= = dateAttribute() = _

  val snapshots = attributeBuilder.map("snapshots", Map[String, Snapshot]())
  val archiveIds = Buffer.empty[String]

  implicit val timeout = Timeout(10 seconds)
  def preStart = {
    CryoEventBus.subscribe(self, "/cryo/datastore")
    datastore ! GetDataStatus("inventory")
  }

  def receive = {
    case DataStatus(status, size) =>
      status match {
        case EntryStatus.Creating => // wait
        case EntryStatus.Created =>
          datastore ! ReadData("inventory", 0, size)
        case EntryStatus.NonExistent =>
          glacier ! RefreshInventory
      }

    case DataRead(id, position, buffer) =>
      // TODO check id and position
      val message = InventoryMessage(buffer.asByteBuffer.asCharBuffer.toString)
//      date = message.date
//      val (s, a) = message.archives.partition(_.archiveType == Index)
//      snapshots ++= s.map { a => a.id -> new RemoteSnapshot(a) }
//      archives ++= a.map { a => a.id -> a }

    case AttributeChange(path, attribute) =>
      val pathRegex = "/cryo/datastore/([^/]*)#(.*)".r
      path match {
        case pathRegex("inventory", "status") => 
	      CryoEventBus.publish(AttributeChange("/cryo/inventory#status", attribute))
	      if (attribute.now == EntryStatus.Created) {
	        datastore ! GetDataStatus("inventory")
	      }
        case pathRegex(id, attr) =>
          if (archiveIds.contains(id))
            CryoEventBus.publish(AttributeChange(s"/cryo/archives/${id}#${attr}", attribute))
            // idem for snapshots ?
      }
    case CreateArchive =>
      val id = UUID.randomUUID().toString
      datastore ! CreateData(id, 0)
      sender ! ArchiveCreated(id)
      
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