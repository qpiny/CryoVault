package org.rejna.cryo.models

import scala.collection.mutable.{ HashMap, ListBuffer, LinkedList }
import scala.collection.JavaConversions._
import scala.concurrent.{ ExecutionContext, Await, Future }
import scala.concurrent.duration._
import scala.language.{ implicitConversions, postfixOps }

import java.io.{ FileOutputStream, FileInputStream, IOException }
import java.util.UUID
import java.nio.{ ByteBuffer, ByteOrder }
import java.nio.file._
import java.nio.file.StandardOpenOption._
import java.nio.file.attribute._
import java.nio.channels.FileChannel

import akka.actor.Actor
import akka.pattern.ask
import akka.util.{ Timeout, ByteString, ByteStringBuilder }

import com.typesafe.config.Config

import sbinary._
import sbinary.DefaultProtocol._
import sbinary.Operations._

import org.joda.time.DateTime

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

sealed abstract class SnapshotRequest extends Request { val id: String }
sealed abstract class SnapshotResponse extends Response
sealed class SnapshotError(message: String, cause: Option[Throwable]) extends CryoError(message, cause.get)

case class SnapshotUpdateFilter(id: String, file: String, filter: FileFilter) extends SnapshotRequest
case class SnapshotGetFiles(id: String, directory: String) extends SnapshotRequest
case object FilterUpdated extends SnapshotResponse
case class SnapshotUpload(id: String) extends SnapshotRequest

//case class ArchiveCreated(id: String) extends SnapshotResponse
//case object CreateSnapshot extends SnapshotRequest
//case class SnapshotCreated(aref: ActorRef) extends SnapshotResponse

class LocalSnapshot(cryoctx: CryoContext, id: String) extends Actor with LoggingClass {
  implicit val ctx = cryoctx
  val attributeBuilder = new AttributeBuilder(s"/cryo/snapshot/${id}")

  val sizeAttribute = attributeBuilder("size", 0L)
  def size = sizeAttribute()
  def size_= = sizeAttribute() = _
  val fileFilters = attributeBuilder.map("fileFilters", Map.empty[String, FileFilter])
  val files = attributeBuilder.list("files", List.empty[String])

  fileFilters <+> new AttributeListCallback {
    override def onListChange[A](attribute: ReadAttribute[List[A]], addedValues: List[A], removedValues: List[A]) = {
      var newSize = size
      var addedFiles = LinkedList.empty[String]
      if (removedValues.isEmpty) {
        val (addedFiles, addedSize) = walkFileSystem(addedValues.asInstanceOf[List[(String, FileFilter)]])
        files ++= addedFiles
        size += addedSize
      } else {
        val (newFiles, newSize) = walkFileSystem(fileFilters)
        files() = newFiles.toList
        size = newSize
      }
    }
  }

  private def walkFileSystem(filters: Iterable[(String, FileFilter)]) = {
    var size = 0L
    var files = LinkedList.empty[String]
    for ((path, filter) <- filters) {
      new TraversePath(path).foreach {
        case (f, attrs) =>
          if (attrs.isRegularFile && filter.accept(path)) {
            files = LinkedList(cryoctx.baseDirectory.relativize(f).toString) append files
            size += attrs.size
          }
      }
    }
    (files, size)
  }

  private def splitFile(f: String) = new Traversable[Block] {
    def foreach[U](func: Block => U) = {
      val input = FileChannel.open(cryoctx.baseDirectory.resolve(f), READ)
      try {
        val buffer = ByteBuffer.allocate(1024) //FIXME blockSizeFor(Files.size(file)))
        Iterator.continually { buffer.clear; input.read(buffer) }
          .takeWhile(_ != -1)
          .filter(_ > 0)
          .foreach(size => { buffer.flip; func(Block(buffer)) })
      } finally {
        input.close
      }
    }
  }

  //  def create = {
  //    // serialize index: [File -> [Hash]] ++ [Hash -> BlockLocation] ++ [File -> Filter]
  //    implicit val timeout = Timeout(10 seconds)
  //    implicit val cxt = context.system.dispatcher
  //    try {
  //      var currentArchiveId = (datastore ? CreateArchive).mapTo[ArchiveCreated].map(_.id)
  //      var currentArchiveSize = 0L
  //      //TODO format[Int].writes(output, files().length)
  //      for (f <- files()) {
  //        log.info(s"add file ${f} in archive")
  //        // TODO format[String].writes(output, f)
  //        for (block <- splitFile(f)) {
  //          if (currentArchiveSize > 10*1024*1024) { //TODO Config.archiveSize) {
  //            // TODO upload archive currentArchiveId.map(glacier
  //            currentArchiveId = (datastore ? CreateArchive).mapTo[ArchiveCreated].map(_.id)
  //          }
  //          val bl = Catalog.getOrUpdate(block, currentArchive.writeBlock(block))
  //          format[Boolean].writes(output, true)
  //          format[Hash].writes(output, block.hash)
  //        }
  //        format[Boolean].writes(output, false)
  //      }
  //      currentArchive.upload
  //
  //      // TODO format[Map[Hash, BlockLocation]].writes(output, Cryo.catalog)
  //      format[Map[String, FileFilter]].writes(output, fileFilters.toMap)
  //    } finally {
  //      output.close
  //    }
  //    // upload
  //    remoteSnapshot = Some(Cryo.migrate(this, upload))
  //  }

  class ArchiveUploader(implicit val timeout: Timeout, val executionContext: ExecutionContext) {
    implicit val byteOrder = ByteOrder.BIG_ENDIAN

    case class BSSerializer(bsBuilder: ByteStringBuilder) {
      def putString(s: String): ByteStringBuilder = {
        val bs = ByteString(s, "UTF-8")
        bsBuilder.putInt(bs.length)
        bsBuilder ++= bs
      }
      def putBlockLocation(bl: BlockLocation): ByteStringBuilder = {
        putHash(bl.hash)
        putString(bl.archiveId)
        bsBuilder.putLong(bl.offset)
        bsBuilder.putInt(bl.size)
        bsBuilder
      }
      def putHash(hash: Hash): ByteStringBuilder = {
        bsBuilder.putBytes(hash.value)
        bsBuilder.putInt(hash.version.getOrElse(0))
      }
      def putBoolean(b: Boolean): ByteStringBuilder = {
        if (b) bsBuilder.putByte(1)
        else bsBuilder.putByte(0)
      }
    }

    implicit def bs2bss(bsBuilder: ByteStringBuilder) = BSSerializer(bsBuilder)

    case class Current(out: ByteStringBuilder, aid: String, len: Int)
    var current = (cryoctx.datastore ? CreateArchive) map {
      case ArchiveCreated(aid) => Current(new ByteStringBuilder, aid, 0)
      case e: CryoError => throw e
      case o: Any => throw new CryoError(s"Unexpected message: ${o}")
    }

    def addFile(filename: String) = {
      current = current.map {
        case Current(out, aid, len) =>
          out.putString(filename)
          Current(out, aid, len)
      }
    }

    private def nextArchiveIfNeeded: Future[Current] = {
      current flatMap {
        case c @ Current(out, aid, len) =>
          if (len > cryoctx.archiveSize) {
            (cryoctx.cryo ? UploadData(aid)) flatMap {
              case DataUploaded => (cryoctx.datastore ? CreateArchive) map {
                case ArchiveCreated(newId) => Current(out, newId, 0)
                case e: CryoError => throw e
                case o: Any => throw new CryoError(s"Unexpected message: ${o}")
              }
              case e: CryoError => throw e
              case o: Any => throw new CryoError(s"Unexpected message: ${o}")
            }
          } else
            Future(c)
      }
    }

    private def writeInArchive(block: Block): Future[Current] = {
      nextArchiveIfNeeded flatMap {
        case c @ Current(out, aid, len) =>
          (cryoctx.datastore ? WriteData(aid, ByteString(block.data))).map {
            case DataWritten(_, position, length) =>
              out.putBlockLocation(BlockLocation(block.hash, aid, position, length.toInt))
              Current(out, aid, len + length.toInt)
            case e: CryoError => throw e
            case o: Any => throw new CryoError(s"Unexpected message: ${o}")
          }
      }
    }

    def writeBlock(block: Block) = {
      current = current flatMap {
        case Current(out, aid, len) =>
          (cryoctx.catalog ? GetBlockLocation(block.hash)) flatMap {
            case BlockLocationNotFound =>
              writeInArchive(block)
            case bl: BlockLocation =>
              out.putBoolean(true)
              out.putHash(bl.hash)
              Future(Current(out, aid, len + block.size))
            case e: CryoError => throw e
            case o: Any => throw new CryoError(s"Unexpected message: ${o}")
          }
      }
    }

    def upload: Future[ByteString] = {
      current flatMap {
        case Current(out, aid, len) =>
          (cryoctx.cryo ? UploadData(aid)) map {
            case DataUploaded => out.result
            case e: CryoError => throw e
            case o: Any => throw new CryoError(s"Unexpected message: ${o}")
          }
      }
    }
  }

  def create = {
    implicit val timeout = Timeout(10 seconds)
    implicit val executionContext = context.system.dispatcher

    var archiveUploader = new ArchiveUploader

    for (f <- files()) {
      archiveUploader.addFile(f)
      for (block <- splitFile(f)) {
        archiveUploader.writeBlock(block)
      }
    }
    //val output = new DataStoreOutputStream(cryoctx, id)
    //    try {
    //      //cryoctx.datastore ! WriteData(id, new ByteStringBuilder().result)
    //      //format[Int].writes(output, files().length)
    //      for (f <- files()) {
    //        log.info(s"add file ${f} in archive")
    //        archiveUploader.addFile(f)
    //        for (block <- splitFile(f)) {
    //          
    //          if (currentArchiveSize > cryoctx.archiveSize)
    //            archiveUploader.upload
    //          val bl = Catalog.getOrUpdate(block, currentArchive.writeBlock(block))
    //          format[Boolean].writes(output, true)
    //          format[Hash].writes(output, block.hash)
    //        }
    //        format[Boolean].writes(output, false)
    //      }
    //      currentArchive.upload
    //
    //      // TODO format[Map[Hash, BlockLocation]].writes(output, Cryo.catalog)
    //      format[Map[String, FileFilter]].writes(output, fileFilters.toMap)
    //    } finally {
    //      output.close
    //    }

  }

  def receive = {
    case SnapshotUpdateFilter(id, file, filter) =>
      fileFilters += file -> filter
      sender ! FilterUpdated
    case SnapshotGetFiles(id, directory) => // TODO
    case SnapshotUpload(id) =>
      context.become(uploading)

  }

  def uploading: Receive = {
    case _ =>
  }
}





















/******************************************************************************************************************************
class RemoteSnapshot(date: DateTime, id: String, size: Long, hash: Hash) extends RemoteArchive(Index, date, id, size, hash) with Snapshot {
  def this(ra: RemoteArchive) = this(ra.date, ra.id, ra.size, ra.hash)

  val remoteFiles = attributeBuilder.list("files", List[RemoteFile]())

  val fileFilters = scala.collection.mutable.Map[String, FileFilter]()

  onStateChange(stop => {
    if (state == Cached) {
      // Load snapshot from file
      import CryoBinary._
      val input = Files.newInputStream(file)
      try {
        Catalog ++= format[Map[Hash, BlockLocation]].reads(input)
        remoteFiles ++= format[Map[String, Iterator[Hash]]].reads(input).map {
          case (f, hashes) => new RemoteFile(id, Config.baseDirectory.resolve(f), hashes.toSeq: _*)
        }
        fileFilters ++= format[Map[String, FileFilter]].reads(input)
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
  val blockLocations = blockHash.map(h => Catalog.get(h).getOrElse(sys.error("Block location is not found in catalog")))
  val archives = blockLocations.map(_.archive.asInstanceOf[RemoteArchive])

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
}*/