package org.rejna.cryo.models

import scala.collection.mutable.{ HashMap, ListBuffer, LinkedList }
import scala.collection.JavaConversions._
import scala.concurrent.{ ExecutionContext, Await, Future }
import scala.concurrent.duration._
import scala.language.{ implicitConversions, postfixOps }
import scala.util.{ Try, Success, Failure }

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
case class FilterUpdated() extends SnapshotResponse
case class SnapshotUpload(id: String) extends SnapshotRequest
case class SnapshotUploaded(id: String) extends SnapshotResponse

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

  case class UploaderState(out: ByteStringBuilder, aid: String, len: Int)
  class ArchiveUploader(implicit val timeout: Timeout, val executionContext: ExecutionContext) {
    import ByteStringSerializer._
    var state: Future[UploaderState] = (cryoctx.datastore ? CreateArchive) map {
      case ArchiveCreated(aid) => UploaderState(new ByteStringBuilder, aid, 0)
      case e: CryoError => throw e
      case o: Any => throw new CryoError(s"Unexpected message: ${o}")
    }

    def addFile(filename: String, blocks: TraversableOnce[Block]) = {
      state = state.map {
        case UploaderState(out, aid, len) =>
          out.putString(filename)
          UploaderState(out, aid, len)
      }
      for (b <- blocks)
        writeBlock(b)
      state = state.map {
        case UploaderState(out, aid, len) =>
          out.putBoolean(false)
          UploaderState(out, aid, len)
      }
    }

    private def nextArchiveIfNeeded: Future[UploaderState] = {
      state.flatMap {
        case us @ UploaderState(out, aid, len) =>
          if (len > cryoctx.archiveSize) {
            (cryoctx.cryo ? UploadData(aid)) flatMap {
              case DataUploaded(_) => (cryoctx.datastore ? CreateArchive) map {
                case ArchiveCreated(newId) => UploaderState(out, newId, 0)
                case e: CryoError => throw e
                case o: Any => throw new CryoError(s"Unexpected message: ${o}")
              }
              case e: CryoError => throw e
              case o: Any => throw new CryoError(s"Unexpected message: ${o}")
            }
          } else
            Future(us)
      }
    }

    private def writeInArchive(block: Block): Future[UploaderState] = {
      nextArchiveIfNeeded flatMap {
        case UploaderState(out, aid, len) =>
          (cryoctx.datastore ? WriteData(aid, ByteString(block.data))).map {
            case DataWritten(_, position, length) =>
              out.putBoolean(true)
              out.putHash(block.hash)
              UploaderState(out, aid, len + length.toInt)
            case e: CryoError => throw e
            case o: Any => throw new CryoError(s"Unexpected message: ${o}")
          }
      }
    }

    private def writeBlock(block: Block) = {
      state = state flatMap {
        case UploaderState(out, aid, len) =>
          (cryoctx.hashcatalog ? GetBlockLocation(block)) flatMap {
            case BlockLocationNotFound(_) =>
              writeInArchive(block)
            case bl: BlockLocation =>
              out.putBoolean(true)
              out.putHash(bl.hashVersion)
              Future(UploaderState(out, aid, len + block.size))
            case e: CryoError => throw e
            case o: Any => throw new CryoError(s"Unexpected message: ${o}")
          }
      }
    }

    def upload: Future[ByteString] = {
      state flatMap {
        case UploaderState(out, aid, len) =>
          (cryoctx.cryo ? UploadData(aid)) map {
            case DataUploaded(_) => out.result
            case e: CryoError => throw e
            case o: Any => throw new CryoError(s"Unexpected message: ${o}")
          }
      }
    }
    
    def flatMap(f: UploaderState => Future[UploaderState]): Unit = {
      state = state.flatMap(f)
    }
  }

  def receive = CryoReceive {
    case SnapshotUpdateFilter(id, file, filter) =>
      fileFilters += file -> filter
      sender ! FilterUpdated
    case SnapshotGetFiles(id, directory) => // TODO
    case SnapshotUpload(id) =>
      implicit val timeout = Timeout(10 seconds)
      implicit val executionContext = context.system.dispatcher
      val requester = sender
      var archiveUploader = new ArchiveUploader
      for (f <- files()) {
        archiveUploader.addFile(f, splitFile(f))
      }
      archiveUploader.flatMap {
        case UploaderState(out, aid, len) =>
          (cryoctx.hashcatalog ? GetCatalogContent) map {
            case CatalogContent(catalog) =>
              //out.putCatalog(catalog)
              UploaderState(out, aid, len)
          }
          
      }
      // TODO format[Map[Hash, BlockLocation]].writes(output, Cryo.catalog)
      //      format[Map[String, FileFilter]].writes(output, fileFilters.toMap)
      archiveUploader.upload.map {
        case bs: ByteString =>
          cryoctx.datastore ? WriteData(id, bs) map {
            case DataWritten => (cryoctx.cryo ? UploadData(id)) map {
              case DataUploaded(sid) => requester ! SnapshotUploaded(sid)
              case e: CryoError => throw e
              case o: Any => throw new CryoError(s"Unexpected message: ${o}")
            }
            case e: CryoError => throw e
            case o: Any => throw new CryoError(s"Unexpected message: ${o}")
          }
      }.onFailure {
        case e: CryoError => requester ! e
        case o: Any => requester ! new CryoError(s"Unexpected message: ${o}")
      }
    //}
    //case Failure(e) => requester ! new CryoError(s"Unexpected error", e)

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