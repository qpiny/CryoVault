package models

import scala.io.Source

import scalax.io.Resource

import akka.util.Duration
import akka.util.duration._

import play.api.libs.json.JsValue

import java.io.{ File, FileOutputStream }
import java.util.UUID

import org.joda.time.{ DateTime, Interval }

import sbinary._
import sbinary.DefaultProtocol._
import sbinary.Operations._

import CryoJson._
import ArchiveType._
import CryoStatus._

class Inventory(implicit cryo: Cryo) {
  val dateAttribute = cryo.attributeBuilder("inventoryDate", new DateTime)
  def date = dateAttribute()
  private def date_= = dateAttribute() = _

  def d(x: List[Tuple2[String, Archive]]): JsValue = ListFormat[Tuple2[String, Archive]](x)
  val snapshots = cryo.attributeBuilder.map("snapshots", Map[String, Snapshot]())(ListFormat[(String, Snapshot)])
  val archives = cryo.attributeBuilder.map("archives", Map[String, Archive]())(d)//(ListFormat[Tuple2[String, Archive]])//(ListFormat[(String, Archive)])

  protected val stateAttribute = cryo.attributeBuilder("state", Remote)
  def state = stateAttribute()
  protected def state_= = stateAttribute() = _

  val file = Config.inventoryFile

  if (file.exists)
    update(file)

  def update(f: File): Unit = update(InventoryMessage(Source.fromFile(f).mkString))

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
      cryo.initiateInventory(jobId => {
        val input = cryo.getJobOutput(jobId)
        val output = new MonitoredOutputStream(cryo.attributeBuilder, "Downloading inventory",
          new FileOutputStream(file),
          input.available)
        Resource.fromInputStream(input) copyDataTo Resource.fromOutputStream(output)
        update(file)
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