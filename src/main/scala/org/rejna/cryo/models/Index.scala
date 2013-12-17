package org.rejna.cryo.models

import scala.collection.mutable.LinkedList
import scala.collection.JavaConversions.iterableAsScalaIterable
import scala.concurrent.Future

import akka.actor.{ Actor, ActorContext }
import akka.util.{ ByteString, ByteStringBuilder }

import java.nio.file.{ Path, Files, AccessDeniedException }
import java.nio.ByteOrder

trait BaseSnapshot {
  def receive: Actor.Receive
  def id: String
}

class Snapshot(_cryoctx: CryoContext, snapshot: BaseSnapshot) extends CryoActor(_cryoctx) {

  def receive = snapshot.receive orElse {
    case GetID() =>
      sender ! ID(snapshot.id)

  }
}

class SnapshotCreating(actor: CryoActor, _id: String)
  extends BaseSnapshot
  with LoggingClass
  with CryoAskSupport {

  implicit val cryoctx = actor.cryoctx
  implicit val executionContext = actor.executionContext
  val attributeBuilder = CryoAttributeBuilder(s"/cryo/snapshot/${id}")

  val fileFilters = attributeBuilder.map("fileFilters", Map.empty[Path, FileFilter])
  val files = attributeBuilder.list("files", List.empty[Path])
  fileFilters <+> updateFiles

  def sender = actor.sender

  def id = _id

  def receive = {
    case SnapshotUpdateFilter(id, file, filter) =>
      val path = cryoctx.filesystem.getPath(file)
      if (cryoctx.baseDirectory.resolve(path).normalize.startsWith(cryoctx.baseDirectory)) {
        if (filter == NoOne)
          fileFilters -= path
        else
          fileFilters += path -> filter
        sender ! FilterUpdated()
      } else {
        sender ! DirectoryTraversalError(path.toString)
      }

    case SnapshotGetFiles(id, path) =>
      val absolutePath = cryoctx.baseDirectory.resolve(path).normalize
      if (!absolutePath.startsWith(cryoctx.baseDirectory)) {
        log.error(s"${absolutePath} doesn't start with ${cryoctx.baseDirectory}; return empty result")
        sender ! SnapshotFiles(id, path, List.empty[FileElement])
      } else try {
        val dirContent = Files.newDirectoryStream(absolutePath)
        val fileElements = for (f <- dirContent) yield {
          val filePath = cryoctx.baseDirectory.relativize(f)
          val fileSize = for (
            fs <- files;
            if fs.startsWith(filePath)
          ) yield Files.size(cryoctx.baseDirectory.resolve(fs))
          new FileElement(filePath, Files.isDirectory(f), fileFilters.get(filePath), fileSize.size, fileSize.sum)
        }
        sender ! SnapshotFiles(id, path, fileElements.toList)
      } catch {
        case e: AccessDeniedException =>
          sender ! SnapshotFiles(id, path, List(new FileElement(
            cryoctx.filesystem.getPath(path).resolve("_Access_denied_"), false, None, 0, 0)))
        case t: Throwable =>
          sender ! CryoError("Error while getting snapshot files", t)
      }

    case SnapshotGetFilter(id, path) =>
      sender ! SnapshotFilter(id, path, fileFilters.get(cryoctx.filesystem.getPath(path)))

    /*
 * Snapshot = Map[Path, FileFilter] + List[File] + Catalog (+ ToC ?)
 * File = MetaData + List[BlockId]
 * Catalog = Map[BlockId, (Hash, BlockLocation)]
 * MetaData = { filename: String; attributes: ?; size: Long }
 * ToC = { fileListOffset: Long; catalogOffset: Long }
 */

    case SnapshotUpload(id) =>
      val _sender = sender
    //val archiveUploader = new IndexBuilder
    //      for (f <- files()) {
    //        archiveUploader.addFile(f, splitFile(f))
    //      }

  }

  private object updateFiles extends AttributeListCallback {
    override def onListChange[A](name: String, addedValues: List[A], removedValues: List[A]) = {
      if (removedValues.isEmpty) {
        val addedFileFilters = addedValues.asInstanceOf[List[(Path, FileFilter)]]
        val (addedFiles, addedSize) = walkFileSystem(addedFileFilters)
        files ++= addedFiles
      } else {
        val (newFiles, newSize) = walkFileSystem(fileFilters)
        files() = newFiles.toList
      }
    }
  }

  // path are relative to baseDirectory
  private def walkFileSystem(filters: Iterable[(Path, FileFilter)]) = {
    var size = 0L
    var files = LinkedList.empty[Path]
    for ((path, filter) <- filters) {
      new TraversePath(cryoctx.baseDirectory.resolve(path)).foreach {
        case (f, attrs) =>
          if (attrs.isRegularFile && filter.accept(f)) {
            val normf = f.normalize
            /* Really usefull ? */
            if (!normf.startsWith(cryoctx.baseDirectory))
              throw DirectoryTraversalError(f.toString)
            files = LinkedList(cryoctx.baseDirectory.relativize(normf)) append files
            size += attrs.size
          }
      }
    }
    (files, size)
  }

  class BuilderState private (indexData: ByteString, aid: String, len: Int) {
    implicit val byteOrder = ByteOrder.BIG_ENDIAN
    def putString(s: String) = {
      val bs = ByteString(s, "UTF-8")
      val bsb = new ByteStringBuilder
      bsb.putInt(bs.length)
      bsb ++= bs
      new BuilderState(indexData ++ bsb.result, aid, len)
    }
    def putPath(p: Path) = putString(p.toString)
    def putInt(i: Int) = {
      val bsb = new ByteStringBuilder
      bsb.putInt(i)
      new BuilderState(indexData ++ bsb.result, aid, len)
    }

    def flush = this // TODOsend indexData to datastore

    def writeBlock(block: Block) = {
      val data = ByteString(block.data)
      (cryoctx.datastore ? WriteData(aid, data)) map {
        case DataWritten(_, _, _) => new BuilderState(indexData, aid, len + data.length)
        case x => throw CryoError("", x)
      }
    }
    
    def addFile()
  }

  object BuilderState {
    def apply = (cryoctx.inventory ? CreateArchive()) map {
      case ArchiveCreated(aid) => new BuilderState(ByteString.empty, aid, 0)
      case o: Any => throw CryoError("Fail to create data", o)
    }
    
  }
  class IndexBuilder(val state: Future[BuilderState]) {
    import ByteStringSerializer._

    def addFile(filename: Path, blocks: TraversableOnce[Block]) = {
      (state.map(_.putPath(filename)) /: blocks) {
        case (st, block) =>
          (cryoctx.hashcatalog ? GetBlockLocation(block)) flatMap {
            case BlockLocationNotFound(_) => st.flatMap(_.writeBlock(block))
            case bl: BlockLocation => st.map(_.putInt(bl.size)) // FIXME will be replace by block ID
            case x => throw CryoError("", x)
          }
      } map (_.flush)
    }
    
  }
}
//    blocks.map {
//      case block =>
//        (cryoctx.hashcatalog ? GetBlockLocation(block)) flatMap {
//          case BlockLocationNotFound(_) =>
//              writeInArchive(block)
//            case bl: BlockLocation =>
//              bb.putBoolean(true)
//              bb.putHash(bl.hashVersion)
//            case e: Any => throw CryoError("Fail to get block location", e)
//        }
//    }
//    }
//      case BuilderState(bsb, aid, len) =>
//        blocks.
//    }
//      val bsb = new ByteStringBuilder
//      bsb.putString(filename.toString)
//      blocks.map {
//        case block => 
//          (cryoctx.hashcatalog ? GetBlockLocation(block)) flatMap {
//            case BlockLocationNotFound(_) =>
//              writeInArchive(block)
//            case bl: BlockLocation =>
//              bb.putBoolean(true)
//              bb.putHash(bl.hashVersion)
//            case e: Any => throw CryoError("Fail to get block location", e)
//          }
//      }
//      }
//    
//    def writeInArchive(block: Block): Future[]
//      state = state.flatMap {
//        case us @ UploaderState(aid, len) =>
//          cryoctx.datastore ? WriteData(id, )
//          out.putString(filename.toString)
//          us
//      }
//      for (b <- blocks)
//        writeBlock(b)
//      state = state.map {
//        case us @ UploaderState(out, aid, len) =>
//          out.putBoolean(false)
//          us
//      }
//    }
//
//    private def nextArchiveIfNeeded: Future[UploaderState] = {
//      state.flatMap {
//        case us @ UploaderState(out, aid, len) =>
//          if (len > cryoctx.archiveSize) {
//            (cryoctx.cryo ? UploadData(aid)) flatMap {
//              case DataUploaded(_) => (cryoctx.datastore ? CreateArchive) map {
//                case ArchiveCreated(newId) => UploaderState(out, newId, 0)
//                case e: CryoError => throw e
//                case o: Any => throw CryoError(s"Unexpected message: ${o}")
//              }
//              case e: CryoError => throw e
//              case o: Any => throw CryoError(s"Unexpected message: ${o}")
//            }
//          } else
//            Future(us)
//      }
//    }
//
//    private def writeInArchive(block: Block): Future[UploaderState] = {
//      nextArchiveIfNeeded flatMap {
//        case UploaderState(out, aid, len) =>
//          (cryoctx.datastore ? WriteData(aid, ByteString(block.data))).map {
//            case DataWritten(_, position, length) =>
//              out.putBoolean(true)
//              out.putHash(block.hash)
//              UploaderState(out, aid, len + length.toInt)
//            case e: Any => throw CryoError("Fail to write data", e)
//          }
//      }
//    }
//
//    private def writeBlock(block: Block) = {
//      state = state flatMap {
//        case UploaderState(out, aid, len) =>
//          (cryoctx.hashcatalog ? GetBlockLocation(block)) flatMap {
//            case BlockLocationNotFound(_) =>
//              writeInArchive(block)
//            case bl: BlockLocation =>
//              out.putBoolean(true)
//              out.putHash(bl.hashVersion)
//              Future(UploaderState(out, aid, len + block.size))
//            case e: Any => throw CryoError("Fail to get block location", e)
//          }
//      }
//    }
//
//    def upload: Future[ByteString] = {
//      state flatMap {
//        case UploaderState(out, aid, len) =>
//          (cryoctx.cryo ? UploadData(aid)) map {
//            case DataUploaded(_) => out.result
//            case e: Any => throw CryoError("Fail to upload data", e)
//          }
//      }
//    }
//
//    def flatMap(f: UploaderState => Future[UploaderState]): Unit = {
//      state = state.flatMap(f)
//    }


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