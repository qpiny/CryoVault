package org.rejna.cryo.models

import scala.collection.mutable.LinkedList
import scala.collection.JavaConversions.iterableAsScalaIterable
import scala.concurrent.Future

import akka.actor.{ Actor, ActorContext, ActorRef }
import akka.util.{ ByteString, ByteStringBuilder }
import akka.event.Logging.Error

import java.io.IOException
import java.nio.file._
import java.nio.file.StandardOpenOption._
import java.nio.file.attribute._
import java.nio.channels.FileChannel
import java.nio.{ ByteOrder, ByteBuffer }
import java.util.UUID


/*
 * Snapshot type:
 * Creating 
 * Uploading
 * Cached
 * Remote
 * Downloading
 */

trait BaseSnapshot {
  def updateFilter(file: String, filter: FileFilter): Unit
  def getFiles(path: String): List[FileElement]
  def getFilter(path: String): Option[FileFilter]
  def upload(): Unit
}

class Snapshot(_cryoctx: CryoContext, val id: UUID) extends CryoActor(_cryoctx) {

  var snapshot: Option[BaseSnapshot] = None

  def receive = cryoReceive {
    case PrepareToDie() =>
      sender ! ReadyToDie()
    case UpdateFilter(id, file, filter) =>
      snapshot.getOrElse(throw InvalidState(s"Uninitialized snapshot : ${id}")).updateFilter(file, filter)
      sender ! Done()
    case GetFileList(id, path) =>
      val files = snapshot.getOrElse(throw InvalidState(s"Uninitialized snapshot : ${id}")).getFiles(path)
      sender ! FileList(id, path, files)
    case GetFilter(id, path) =>
      val filter = snapshot.getOrElse(throw InvalidState(s"Uninitialized snapshot : ${id}")).getFilter(path)
      sender ! SnapshotFilter(id, path, filter)
    case Upload(id, _) =>
      snapshot.getOrElse(throw InvalidState(s"Uninitialized snapshot : ${id}")).upload
      sender ! Uploaded(id)
    case GetID() =>
      sender ! id
    case ObjectStatus.Creating() =>
      if (snapshot.isDefined)
        throw InvalidState(s"Already initialized snapshot : ${id}")
      snapshot = Some(new SnapshotCreating(cryoctx, id))
  }
}

class SnapshotCreating(_cryoctx: CryoContext, val id: UUID)
  extends BaseSnapshot
  with LoggingClass
  with CryoAskSupport
  with ErrorGenerator {

  implicit val cryoctx = _cryoctx
  implicit val executionContext = cryoctx.executionContext
  val attributeBuilder = CryoAttributeBuilder(s"/cryo/snapshot/${id}")

  val fileFilters = attributeBuilder.map("fileFilters", Map.empty[Path, FileFilter])
  val files = attributeBuilder.list("files", List.empty[Path])
  fileFilters <+> updateFiles

  def updateFilter(file: String, filter: FileFilter): Unit = {
    val path = cryoctx.filesystem.getPath(file)
    if (!cryoctx.baseDirectory.resolve(path).normalize.startsWith(cryoctx.baseDirectory))
      throw DirectoryTraversalError(path.toString)
    if (filter == NoOne)
      fileFilters -= path
    else
      fileFilters += path -> filter
  }

  def getFiles(path: String): List[FileElement] = {
    val absolutePath = cryoctx.baseDirectory.resolve(path).normalize
    if (!absolutePath.startsWith(cryoctx.baseDirectory)) {
      log.error(s"${absolutePath} doesn't start with ${cryoctx.baseDirectory}; return empty result")
      List.empty[FileElement]
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
      fileElements.toList
    } catch {
      case e: AccessDeniedException =>
        new FileElement(cryoctx.filesystem.getPath(path).resolve("_Access_denied_"), false, None, 0, 0) :: Nil
      case t: Throwable =>
        throw cryoError("Error while getting snapshot files", t)
    }
  }

  def getFilter(path: String): Option[FileFilter] =
    fileFilters.get(cryoctx.filesystem.getPath(path))

  /*
 * Snapshot = Map[Path, FileFilter] + List[File] + Catalog (+ ToC ?)
 * File = MetaData + List[BlockId]
 * Catalog = Map[BlockId, (Hash, BlockLocation)]
 * MetaData = { filename: String; attributes: ?; size: Long }
 * ToC = { fileListOffset: Long; catalogOffset: Long }
 */

  def upload: Unit /* FIXME snapshotCached */ = {
    //val _sender = sender
    val builder = new IndexBuilder

    builder.addFilters(fileFilters())
    builder.addFiles(files())
    builder.addCatalog()
    builder.save()
    
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

  class IndexBuilder {
    import ByteStringSerializer._

    case class State(index: ByteStringBuilder, blockIds: Set[Long], aid: UUID, size: Int) // WARNING data is mutable

    implicit class IndexOps(current: Future[State]) {

      def writeBlock(block: Block): Future[State] = {
        current.flatMap {
          case State(index, blockIds, aid, len) =>
            val data = ByteString(block.data)
            (cryoctx.datastore ? WriteData(aid, data))
              .eflatMap("Fail to write block data", {
                case DataWritten(_, pos, _) =>
                  cryoctx.hashcatalog ? AddBlock(block, aid, pos)
              }).eflatMap("Fail to update hash catalog", {
                case BlockAdded(bid) =>
                  index.putLong(bid)

                  if (len + data.length >= cryoctx.archiveSize) {
                    (cryoctx.cryo ? Upload(aid, DataType.Data))
                      .eflatMap("Fail to upload data", {
                        case Uploaded(_) => (cryoctx.inventory ? CreateArchive())
                      }).emap("Fail to create new archive", {
                        case Created(newId) => State(index, blockIds, newId, 0)
                      })
                  } else {
                    Future(State(index, blockIds + bid, aid, len + data.length))
                  }
              })
        }
      }

      def putPath(path: Path): Future[State] = {
        current.map {
          case s @ State(index, blockIds, aid, len) =>
            index.putPath(path)
            s
        }
      }

      def putInt(i: Int): Future[State] = {
        current.map {
          case s @ State(index, blockIds, aid, len) =>
            index.putInt(i)
            s
        }
      }

      def putLong(l: Long): Future[State] = {
        current.map {
          case s @ State(index, blockIds, aid, len) =>
            index.putLong(l)
            s
        }
      }

      def putString(string: String): Future[State] = {
        current.map {
          case s @ State(index, blockIds, aid, len) =>
            index.putString(string)
            s
        }
      }

      def putBlockLocation(bl: BlockLocation): Future[State] = {
        current.map {
          case s @ State(index, blockIds, aid, len) =>
            //id: Int, hash: Hash, archiveId: UUID, offset: Long, size: Int
            index.putLong(bl.id)
            val hash = bl.hash.value
            index.putByte(hash.length.asInstanceOf[Byte])
            index.putBytes(hash)
            index.putLong(bl.archiveId.getLeastSignificantBits)
            index.putLong(bl.archiveId.getMostSignificantBits)
            index.putLong(bl.offset)
            index.putInt(bl.size)
            s
        }
      }
    }

    var state = (cryoctx.inventory ? CreateArchive())
      .emap("Fail to create data", {
        case Created(aid) => State(new ByteStringBuilder, Set.empty, aid, 0)
      })

    def addFiles(files: List[Path]) = {
      state = state.putInt(files.length)
      for (f <- files)
        addFile(f, splitFile(f))
    }

    private def addFile(filename: Path, blocks: TraversableOnce[Block]) = {
      state = state.putPath(filename)
      state = (state /: blocks) {
        case (st, block) =>
          (cryoctx.hashcatalog ? ReserveBlock(block))
            .eflatMap("", {
              case Done() => st.writeBlock(block)
              case bl: BlockLocation => st.putLong(bl.id)
            })
      }
      state = state.putLong(-1) // end of block list
    }

    def addFilters(filters: List[(Path, FileFilter)]) = {
      state = state.putInt(filters.length)
      for ((path, filter) <- filters) {
        state = state.putPath(path)
        state = state.putString(filter.toString)
      }
    }

    def addCatalog() = {
      state = state.flatMap {
        case s @ State(index, blockIds, aid, len) =>
          (cryoctx.hashcatalog ? GetCatalogContent(Some(blockIds)))
            .emap("Fail to get catalog", {
              case CatalogContent(catalog) =>
                index.putInt(catalog.length)
                for (bl <- catalog)
                  index.putBlockLocation(bl)
                s
            })
      }
    }

    def save() = {
      state = state.flatMap({
        case s @ State(index, blockIds, aid, len) =>
          if (len > 0)
            (cryoctx.cryo ? Upload(aid, DataType.Data))
              .emap("Fail to upload data", {
                case Uploaded(_) => State(index, blockIds, null, 0)
              })
          else
            (cryoctx.inventory ? DeleteArchive(aid))
              .emap("Fail to remove unused archive", {
                case Deleted(_) => State(index, blockIds, null, 0)
              })
      }).flatMap({
        case s @ State(index, blockIds, aid, len) =>
          (cryoctx.datastore ? WriteData(id, index.result))
            .eflatMap("Fail to write index data", {
              case DataWritten(_, _, _) => (cryoctx.cryo ? Upload(id, DataType.Index))
            }).emap("Fail to upload index", {
              case Uploaded(_) => State(new ByteStringBuilder, Set.empty, aid, 0)
            })
      })
    }
  }

  private def splitFile(f: Path) = new Traversable[Block] {
    def foreach[U](func: Block => U) = {
      val input = FileChannel.open(cryoctx.baseDirectory.resolve(f), READ)
      try {
        val buffer = ByteBuffer.allocate(1024) //FIXME blockSizeFor(Files.size(file)))
        Iterator.continually { buffer.clear; input.read(buffer) }
          .takeWhile(_ != -1)
          .filter(_ > 0)
          .foreach(size => {
            buffer.flip
            func(Block(buffer)(cryoctx))
          })
      } finally {
        input.close
      }
    }
  }
}

class SnapshotCreated(_cryoctx: CryoContext, val id: UUID)
  extends BaseSnapshot {
  def updateFilter(file: String, filter: FileFilter): Unit = {}
  def getFiles(path: String): List[FileElement] = Nil
  def getFilter(path: String): Option[FileFilter] = None
  def upload(): Unit = {}
}

class TraversePath(path: Path) extends Traversable[(Path, BasicFileAttributes)] {
  //def this(path: String) = this(FileSystems.getDefault.getPath(path))

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