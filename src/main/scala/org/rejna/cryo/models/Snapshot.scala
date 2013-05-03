package org.rejna.cryo.models

import ArchiveType._;
import CryoBinary._;

import scala.collection.mutable.{ HashMap, ListBuffer }
import scala.collection.JavaConversions._
import scala.io.Source

import scalax.io.Resource

import akka.actor._

import java.io.{ File, FileFilter, FileOutputStream, FileInputStream }
import java.util.UUID

import ArchiveType._
import CryoStatus._

import sbinary._
import sbinary.DefaultProtocol._
import sbinary.Operations._

import org.joda.time.DateTime

import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.{ IOFileFilter, TrueFileFilter, WildcardFileFilter }

trait Snapshot { //self: Archive =>
  val date: DateTime
  def size: Long
  val id: String
  def state: CryoStatus
  val attributeBuilder: AttributeBuilder
  val fileFilters: scala.collection.mutable.Map[String, String]
}

protected class LocalSnapshot(id: String) extends LocalArchive(Index, id) with Snapshot {

  protected val remoteSnapshotAttribute = attributeBuilder[Option[RemoteSnapshot]]("remoteSnapshot", None)
  def remoteSnapshot: Option[RemoteSnapshot] = remoteSnapshotAttribute()
  protected def remoteSnapshot_= = remoteSnapshotAttribute() = _

  val fileFilters = attributeBuilder.map("fileFilters", Map[String, String]())

  val files = attributeBuilder.list("files", () => {
    fileFilters.flatMap(df => {
      val filterBase = new File(Config.baseDirectory, df._1)
      if (filterBase.isDirectory)
        FileUtils.iterateFiles(filterBase, new WildcardFileFilter(df._2), TrueFileFilter.INSTANCE)
      else
        Some(filterBase)
    }).toList.distinct.map(f => Config.baseURI.relativize(f.toURI).getPath)
  })

  files <* fileFilters

  files <+> new AttributeListCallback {
    override def onListChange[A, B](attribute: ReadAttribute[List[A]], addedValues: List[B], removedValues: List[B]) = {
      size += addedValues.map(f => new File(Config.baseDirectory, f.toString).length()).sum -
        removedValues.map(f => new File(Config.baseDirectory, f.toString).length()).sum
    }
  }

  private def splitFile(fileURI: String): Iterator[Block] = {
    val file = new File(Config.baseDirectory, fileURI)
    val blockSize = Config.blockSizeFor(file.length)
    Source.fromFile(file)(scala.io.Codec.ISO8859).sliding(blockSize, blockSize).map(data => Block(data.map(_.toByte).toArray)) // FIXME close the file
  }

  def create = {
    import CryoBinary._
    if (state != Creating) throw InvalidStateException
    // TODO check hash collision
    
    // serialize index: [File -> [Hash]] ++ [Hash -> BlockLocation] ++ [File -> IOFileFilter]
    println("Creating archive in %s".format(file.getAbsolutePath))
    val output = new FileOutputStream(file)
    try {
      var currentArchive = Cryo.newArchive(Data)
      output.write(files().length)
      for (f <- files()) {
        println("add file %s in archive".format(f))
        format[String].writes(output, f)
        for (block <- splitFile(f)) {
          if (currentArchive.size > Config.archiveSize) {
            currentArchive.upload
            currentArchive = Cryo.newArchive(Data)
          }
          val bl = Cryo.getOrUpdateBlockLocation(block.hash, currentArchive.writeBlock(block))
          output.write(0)
          format[Hash].writes(output, block.hash)
        }
        output.write(1)
      }

      // TODO format[Map[Hash, BlockLocation]].writes(output, Cryo.catalog)
      format[Map[String, String]].writes(output, fileFilters.toMap)
    } finally {
      output.close
    }
    // upload
    remoteSnapshot = Some(Cryo.migrate(this, upload))
  }
}

class RemoteSnapshot(date: DateTime, id: String, size: Long, hash: Hash) extends RemoteArchive(Index, date, id, size, hash) with Snapshot {
  def this(ra: RemoteArchive) = this(ra.date, ra.id, ra.size, ra.hash)

  val remoteFiles = attributeBuilder.list("files", List[RemoteFile]())

  val fileFilters = scala.collection.mutable.Map[String, String]()

  onStateChange(stop => {
    //implicit val _cryo = cryo
    if (state == Cached) {
      import CryoBinary._
      val input = new FileInputStream(file)
      Cryo.updateCatalog(format[Map[Hash, BlockLocation]].reads(input))
      remoteFiles ++= format[Map[String, Iterator[Hash]]].reads(input).map(fh => new RemoteFile(id, new File(Config.baseDirectory, fh._1), fh._2.toSeq: _*))
      fileFilters ++= format[Map[String, String]].reads(input)
      //index ++= sbinary.Operations.fromFile[List[RemoteFile]](file)
      stop
    }
  })

  def restore(filter: IOFileFilter) = {
    if (state != Cached) throw InvalidStateException

    for (rf <- remoteFiles if filter.accept(rf.file))
      rf.restore
  }
}

class RemoteFile(val snapshotId: String, val file: File, val blockHash: Hash*) {

  val statusAttribute = Cryo.attributeBuilder("status", Remote)
  def status = statusAttribute()
  def status_= = statusAttribute() = _
  val blockLocations = blockHash.map(h => Cryo.catalog(h))
  val archives = blockLocations.map(_.archive.asInstanceOf[RemoteArchive]) //List(blockLocations.map(_.archive.asInstanceOf[RemoteArchive]): _*)

  val remoteArchives = Cryo.attributeBuilder.list("remoteArchive", List[RemoteArchive]())
  remoteArchives ++ archives.map(archive =>
    if (archive.state == Cached) {
      None
    } else {
      archive.onStateChange(stop => {
        if (archive.state == Cached) {
          remoteArchives -= archive
          stop
        }
      })
      Some(archive)
    }).flatten

  remoteArchives.onRemove(stop => {
    if (status == Downloading && remoteArchives.isEmpty) {
      writeFile
      stop
    }
  })

  private def writeFile = {
    val out = Resource.fromFile(file) // TODO select destination directory
    for (bl <- blockLocations)
      Resource.fromInputStream(bl.read) copyDataTo out // FIXME check in out is not closed
  }

  def restore = {
    if (remoteArchives.isEmpty) {
      writeFile
    } else {
      status = Downloading
      for (a <- remoteArchives) a.download
    }
  }
}