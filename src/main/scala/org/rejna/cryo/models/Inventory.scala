package org.rejna.cryo.models

import scala.io.Source
import scala.concurrent.duration._
import scala.language.postfixOps
import java.io.FileOutputStream
import java.nio.file.{ Files, Path }
import java.nio.file.StandardOpenOption._
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import org.joda.time.{ DateTime, Interval }
import ArchiveType._
import CryoStatus._
import java.nio.CharBuffer

class Inventory {
  val attributeBuilder = Cryo.attributeBuilder / "inventory"
  val dateAttribute = attributeBuilder("date", DateTime.now)
  def date = dateAttribute()
  private def date_= = dateAttribute() = _

  val snapshots = Cryo.attributeBuilder.map("snapshots", Map[String, Snapshot]())
  val archives = Cryo.attributeBuilder.map("archives", Map[String, Archive]())

  protected val stateAttribute = attributeBuilder("state", Remote)
  def state = stateAttribute()
  protected def state_= = stateAttribute() = _

  val file = Config.inventoryFile

  if (Files.exists(file))
    update(file)
  else
    update(0 second)

  def update(f: Path): Unit = {
    val channel = FileChannel.open(f, READ)
    try {
      val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size)
      buffer.flip
      update(InventoryMessage(buffer.asCharBuffer.toString))
    } finally {
      channel.close
    }
  }

  def update(message: InventoryMessage): Unit = {
    date = message.date
    val sa = message.archives.partition(_.archiveType == Index)
    snapshots ++= sa._1.map { a => a.id -> new RemoteSnapshot(a) }
    archives ++= sa._2.map { a => a.id -> a }
    state = Cached
  }

  def update(maxAge: Duration): Unit = {
    if (state != Downloading && maxAge < (new Interval(date, new DateTime).toDurationMillis millis)) {
      state = Downloading
      Cryo.initiateInventory(jobId => {
        val input = Cryo.getJobOutput(jobId)
        val output = new MonitoredOutputStream(Cryo.attributeBuilder, "Downloading inventory",
          Files.newOutputStream(file, CREATE_NEW),
          input.available)
        try {
          StreamOps.copyStream(input, output)
          update(file)
        } finally {
          input.close
          output.close
        }
      })
    }
  }

  def newArchive(archiveType: ArchiveType, id: String) = {
    archiveType match {
      case Data =>
        val a = new LocalArchive(archiveType, id)
        archives += id -> a
        a
      case Index =>
        val a = new LocalSnapshot(id)
        snapshots += id -> a
        a
    }
  }

  def migrate(archive: LocalArchive, newId: String, size: Long, hash: Hash) = {
    Files.move(archive.file, Config.getFile(archive.archiveType, newId))
    val r = new RemoteArchive(archive.archiveType, archive.date, newId, size, hash)
    archives -= archive.id
    archives += newId -> r
    r
  }

  def migrate(snapshot: LocalSnapshot, archive: RemoteArchive) = {
    var r = new RemoteSnapshot(archive.date, archive.id, archive.size, archive.hash)
    snapshots -= snapshot.id
    snapshots += r.id -> r
    r
  }
}