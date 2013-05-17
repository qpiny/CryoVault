package org.rejna.cryo.models

import scala.io.Source
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.Actor

import java.io.FileOutputStream
import java.nio.file.{ Files, Path }
import java.nio.file.StandardOpenOption._
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.util.UUID

import org.joda.time.{ DateTime, Interval }

import ArchiveType._
import CryoStatus._

case class LoadInventoryFromFile(file: Path)
case class LoadInventoryFromMessage(message: InventoryMessage)
case class RefreshInventory(maxAge: Duration)
case class NewArchive(archiveType: ArchiveType, id: String)
case class MigrateArchive(archive: LocalArchive, newId: String, size: Long, hash: Hash)
case class MigrateSnapshot(snapshot: LocalSnapshot, archive: RemoteArchive)

class Inventory(val attributeBuilder: AttributeBuilder) extends Actor with LoggingClass {
  val cryo = context.actorFor("/user/cryo")

  //val attributeBuilder = Cryo.attributeBuilder / "inventory"
  val dateAttribute = attributeBuilder("date", DateTime.now)
  def date = dateAttribute()
  private def date_= = dateAttribute() = _

  val snapshots = attributeBuilder.map("snapshots", Map[String, Snapshot]())
  val archives = attributeBuilder.map("archives", Map[String, Archive]())

  protected val stateAttribute = attributeBuilder("state", Remote)
  def state = stateAttribute()
  protected def state_= = stateAttribute() = _

  val file = Config.inventoryFile

  def preStart = {
    if (Files.exists(file))
      self ! LoadInventoryFromFile(file)
    else // check if InventoryRetrieval job is already in jobList
      self ! RefreshInventory(0 second)
  }

  def receive = {
    case LoadInventoryFromFile(file) =>
      val channel = FileChannel.open(file, READ)
      try {
        val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size)
        buffer.flip
        self ! LoadInventoryFromMessage(InventoryMessage(buffer.asCharBuffer.toString))
      } finally {
        channel.close
      }

    case LoadInventoryFromMessage(message) =>
      date = message.date
      val sa = message.archives.partition(_.archiveType == Index)
      snapshots ++= sa._1.map { a => a.id -> new RemoteSnapshot(a) }
      archives ++= sa._2.map { a => a.id -> a }
      state = Cached

    case RefreshInventory(maxAge) =>
      if (state != Downloading && maxAge < (new Interval(date, new DateTime).toDurationMillis millis)) {
        state = Downloading
        cryo ! InitiateInventoryRequest
      }

    case NewArchive(archiveType, id) => // TODO create actor for each archive
      archiveType match {
        case Data =>
          val a = new LocalArchive(archiveType, id)
          archives += id -> a
        case Index =>
          val a = new LocalSnapshot(id)
          snapshots += id -> a
      }

    case MigrateArchive(archive, newId, size, hash) =>
      // TODO create an actor which manage store (all archive files)
      Files.move(archive.file, Config.getFile(archive.archiveType, newId))
      val r = new RemoteArchive(archive.archiveType, archive.date, newId, size, hash)
      archives -= archive.id
      archives += newId -> r

    case MigrateSnapshot(snapshot, archive) =>
      var r = new RemoteSnapshot(archive.date, archive.id, archive.size, archive.hash)
      snapshots -= snapshot.id
      snapshots += r.id -> r
  }
}