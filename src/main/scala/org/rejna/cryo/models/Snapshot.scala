package org.rejna.cryo.models

import ArchiveType._;
import CryoBinary._;

import scala.collection.mutable.{ HashMap, ListBuffer, LinkedList }
import scala.collection.JavaConversions._
import scala.io.Source

//import scalax.io.Resource

import akka.actor._

import java.io.{ FileOutputStream, FileInputStream, IOException }
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.file._
import java.nio.file.StandardOpenOption._
import java.nio.file.attribute._
import java.nio.channels.FileChannel

import ArchiveType._
import CryoStatus._

import sbinary._
import sbinary.DefaultProtocol._
import sbinary.Operations._

import org.joda.time.DateTime

trait Snapshot { //self: Archive =>
  val date: DateTime
  def size: Long
  val id: String
  def state: CryoStatus
  val attributeBuilder: AttributeBuilder
  val fileFilters: scala.collection.mutable.Map[String, String]
}

class TraversePath(path: Path) extends Traversable[(Path, BasicFileAttributes)] {
  def this(path: String) = this(FileSystems.getDefault.getPath(path))

  def foreach[U](f: ((Path, BasicFileAttributes)) => U) {
    class Visitor extends SimpleFileVisitor[Path] {
      override def visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = try {
        f(file -> attrs)
        FileVisitResult.CONTINUE
      } catch {
        case _: Throwable => FileVisitResult.TERMINATE
      }
    }
    Files.walkFileTree(path, new Visitor)
  }
}

protected class LocalSnapshot(id: String) extends LocalArchive(Index, id) with Snapshot {

  protected val remoteSnapshotAttribute = attributeBuilder[Option[RemoteSnapshot]]("remoteSnapshot", None)
  def remoteSnapshot: Option[RemoteSnapshot] = remoteSnapshotAttribute()
  protected def remoteSnapshot_= = remoteSnapshotAttribute() = _

  val fileFilters = attributeBuilder.map("fileFilters", Map.empty[String, String])

  val files = attributeBuilder.list("files", List.empty[String])

  fileFilters <+> new AttributeListCallback {
    override def onListChange[A](attribute: ReadAttribute[List[A]], addedValues: List[A], removedValues: List[A]) = {
      var newSize = size
      var addedFiles = LinkedList.empty[String]
      if (removedValues.isEmpty) {
        val (addedFiles, addedSize) = walkFileSystem(addedValues.asInstanceOf[List[(String, String)]])
        files ++= addedFiles.toList // FIXME API collision ++=(TraversableOnce) and ++=(List)
        size += addedSize
      } else {
        val (newFiles, newSize) = walkFileSystem(fileFilters)
        files() = newFiles.toList
        size = newSize
      }
    }
  }

  private def walkFileSystem(filters: Iterable[(String, String)]) = {
    var size = 0L
    var files = LinkedList.empty[String]
    for ((path, filter) <- filters) {
      new TraversePath(path).foreach {
        case (f, attrs) =>
          if (attrs.isRegularFile) {
            files = LinkedList(f.toString) append files // FIXME relativize + check if toString has correct return
            size += attrs.size
          }
      }
    }
    (files, size)
  }

  private def splitFile(f: String) = new  Traversable[Block] {
//    val file = new File(Config.baseDirectory, fileURI)
//    val blockSize = Config.blockSizeFor(file.length)
//    Source.fromFile(file)(scala.io.Codec.ISO8859).sliding(blockSize, blockSize).map(data => Block(data.map(_.toByte).toArray)) // FIXME close the file
    def foreach[U](func: Block => U) = {
      val input = FileChannel.open(Config.baseDirectory.resolve(f), READ)
      try {
	      val buffer = ByteBuffer.allocate(Config.blockSizeFor(Files.size(file)))
	      Iterator.continually(input.read(buffer)) takeWhile (_ != -1) filter (_ > 0) foreach(i => func(Block(buffer.array)))
      } finally {
        input.close
      }
    }
  }

  def create = {
    import CryoBinary._
    if (state != Creating) throw InvalidStateException
    // TODO check hash collision

    // serialize index: [File -> [Hash]] ++ [Hash -> BlockLocation] ++ [File -> Filter]
    println(s"Creating archive in ${file}")
    val output = Files.newOutputStream(file, WRITE, CREATE_NEW)
    try {
      var currentArchive = Cryo.newArchive(Data)
      format[Int].writes(output, files().length)
      for (f <- files()) {
        println(s"add file ${f} in archive")
        format[String].writes(output, f)
        for (block <- splitFile(f)) {
          if (currentArchive.size > Config.archiveSize) {
            currentArchive.upload
            currentArchive = Cryo.newArchive(Data)
          }
          val bl = Cryo.getOrUpdateBlockLocation(block.hash, currentArchive.writeBlock(block))
          format[Boolean].writes(output, true)
          format[Hash].writes(output, block.hash)
        }
        format[Boolean].writes(output, false)
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
      // Load snapshot from file
      import CryoBinary._
      val input =  Files.newInputStream(file)
      try {
	      Cryo.updateCatalog(format[Map[Hash, BlockLocation]].reads(input))
	      remoteFiles ++= format[Map[String, Iterator[Hash]]].reads(input).map {
	        case (f, hashes) => new RemoteFile(id, Config.baseDirectory.resolve(f), hashes.toSeq: _*)
	      }
	      fileFilters ++= format[Map[String, String]].reads(input)
	      //index ++= sbinary.Operations.fromFile[List[RemoteFile]](file)
	      stop
      } finally {
        input.close
      }
    }
  })

  def restore(filter: FileFilter) = {
    if (state != Cached) throw InvalidStateException

    for (rf <- remoteFiles if filter.accept(rf.file))
      rf.restore
  }
}

class RemoteFile(val snapshotId: String, val file: Path, val blockHash: Hash*) {

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
    val out = FileChannel.open(file, WRITE, CREATE_NEW) // TODO select destination directory
    try {
	    for (bl <- blockLocations)
	      out.write(bl.read)
    } finally {
      out.close
    }
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