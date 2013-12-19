package org.rejna.cryo.models

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{ ActorRefFactory }
import akka.util.{ ByteString, ByteIterator, Timeout }
import akka.event.Logging.Error

import java.io.{ InputStream, OutputStream, IOException }
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption._
import java.nio.ByteBuffer
import java.util.{ Date, UUID }

import DataType._

sealed abstract class DatastoreRequest extends Request
sealed abstract class DatastoreResponse extends Response { val id: String }
sealed abstract class DatastoreError(message: String, cause: Throwable) extends GenericError {
  val source = classOf[Datastore].getName
  val marker = Markers.errMsgMarker
}

case class CreateData(idOption: Option[UUID], dataType: DataType, size: Long = 0L) extends DatastoreRequest
case class DataCreated(id: UUID) extends DatastoreResponse

case class DefineData(id: UUID, glacierId: String, dataType: DataType, creationDate: Date, size: Long, checksum: String) extends DatastoreRequest
case class DataDefined(id: UUID) extends DatastoreResponse

case class DeleteData(id: UUID) extends DatastoreRequest
case class DataDeleted(id: UUID) extends DatastoreResponse

case class WriteData(id: UUID, position: Long, buffer: ByteString) extends DatastoreRequest
object WriteData { def apply(id: UUID, buffer: ByteString): WriteData = WriteData(id, -1, buffer) } // AppendData
case class DataWritten(id: UUID, position: Long, length: Long) extends DatastoreResponse

case class ReadData(id: UUID, position: Long, length: Int) extends DatastoreRequest
case class DataRead(id: UUID, position: Long, buffer: ByteString) extends DatastoreResponse

case class ClearLocalCache(id: UUID) extends DatastoreRequest
case class LocalCacheCleared(id: UUID) extends DatastoreResponse

case class PrepareDownload(id: UUID) extends DatastoreRequest
case class DownloadPrepared(id: UUID) extends DatastoreResponse

case class CloseData(id: UUID) extends DatastoreRequest
case class DataClosed(id: UUID) extends DatastoreResponse

case class PackData(id: UUID, glacierId: String) extends DatastoreRequest
case class DataPacked(id: UUID, glacierId: String) extends DatastoreResponse

case class GetDataStatus(id: Either[UUID, String]) extends DatastoreRequest
object GetDataStatus {
  def apply(id: UUID) = new GetDataStatus(Left(id))
  def apply(glacierId: String) = new GetDataStatus(Right(glacierId))
}
case class DataStatus(id: UUID, dataType: DataType, creationDate: Date, status: ObjectStatus, size: Long, checksum: String) extends DatastoreResponse

case class OpenError(message: String, cause: Throwable = Error.NoCause) extends DatastoreError(message, cause)
case class WriteError(message: String, cause: Throwable = Error.NoCause) extends DatastoreError(message, cause)
case class ReadError(message: String, cause: Throwable = Error.NoCause) extends DatastoreError(message, cause)
case class DataNotFoundError(id: Either[UUID, String], message: String, cause: Throwable = Error.NoCause) extends DatastoreError(message, cause)
case class InvalidDataStatus(message: String, cause: Throwable = Error.NoCause) extends DatastoreError(message, cause)

// TODO Replace all internal ID string with UUID
// this makes no confusion with glacier ID
class Datastore(_cryoctx: CryoContext) extends CryoActor(_cryoctx) {
  val attributeBuilder = CryoAttributeBuilder("/cryo/datastore")
  val repository = attributeBuilder.map("repository", Map.empty[UUID, DataEntry])

  override def postStop = try {
    val channel = FileChannel.open(cryoctx.workingDirectory.resolve("repository"), WRITE, CREATE)
    try {
      channel.truncate(0)
      log.debug("Writting repository :")
      repository.values.map { d => log.debug(s"${d.status} | ${d.size} | ${d.id}") }
      val repo = repository.values
        // FIXME        .filter { d => d.status == ObjectStatus.Cached || d.status == Remote }
        .map { _.state }
      channel.write(ByteBuffer.wrap(Json.write(repo).getBytes))
    } catch {
      case t: Throwable => log(CryoError("Datastore has failed to store its state", t))
    } finally {
      channel.close
    }
  } catch {
    case t: Throwable => log(CryoError("Datastore has failed to open its state file", t))
  }

  override def preStart = try {
    val source = scala.io.Source.fromFile(cryoctx.workingDirectory.resolve("repository").toFile)
    val repoData = source.getLines mkString "\n"
    source.close()
    val entries = Json.read[List[DataStatus]](repoData) map {
      case state => DataEntry(cryoctx, attributeBuilder, state)
    }
    repository ++= entries.map(e => e.id -> e)
    log.info("Repository loaded : " + entries.map(_.id).mkString(","))
  } catch {
    case e: IOException => log.warn("Repository file not found")
    case t: Throwable => log.error("Repository load failed", t)
  }

  def getDataEntry(id: UUID): DataEntry = {
    repository.get(id) match {
      case Some(de) => de
      case None => throw DataNotFoundError(Left(id), s"Data ${id} not found")
    }
  }

  def receive = cryoReceive {
    case PrepareToDie() => sender ! ReadyToDie()

    case CreateData(idOption, dataType, size) =>
      val id = idOption.getOrElse {
        var i = UUID.randomUUID
        while (repository contains i) {
          i = UUID.randomUUID
        }
        i
      }
      repository.get(id) match {
        case Some(d: DataEntryCreated) =>
          d.close
          repository += id -> new DataEntryCreating(cryoctx, id, dataType, size, attributeBuilder / id)
          sender ! DataCreated(id)
        case None =>
          repository += id -> new DataEntryCreating(cryoctx, id, dataType, size, attributeBuilder / id)
          sender ! DataCreated(id)
        case Some(d) =>
          sender ! OpenError(s"Data ${id} can't be created (${d.status})")
      }

    case DefineData(id, glacierId, dataType, creationDate, size, checksum) =>
      repository.get(id) match {
        case Some(_) => // data already defined, ignore it
        case None =>
          repository += id -> new DataEntryRemote(cryoctx, id, glacierId, dataType, creationDate, size, checksum, attributeBuilder / id)
      }
      sender ! DataDefined(id)

    case DeleteData(id) =>
      repository.remove(id) match {
        case Some(e) =>
          Files.deleteIfExists(e.file)
          sender ! DataDeleted(id)
        case None =>
          sender ! DataNotFoundError(Left(id), s"Data ${id} not found")
      }

    case WriteData(id, position, buffer) =>
      val de = getDataEntry(id)
      sender ! DataWritten(id, position, de.write(position, buffer))

    case GetDataStatus(Left(id)) =>
      val de = getDataEntry(id)
      sender ! de.state

    case GetDataStatus(Right(glacierId)) =>
      repository.collectFirst {
        case (_, de: DataEntry) if de.glacierId.isDefined && de.glacierId.get == glacierId => sender ! de.state
      } getOrElse {
        sender ! DataNotFoundError(Right(glacierId), s"Data ${glacierId} not found")
      }

    case ReadData(id, position, length) =>
      val de = getDataEntry(id)
      sender ! DataRead(id, position, de.read(position, length))

    case ClearLocalCache(id) =>
      val de = getDataEntry(id)
      val newde = de.clearLocalCache
      repository += id -> newde
      CryoEventBus.publish(AttributeChange(s"/cryo/datastore/${id}#status", Some(de.status), newde.status))
      sender ! LocalCacheCleared(id)

    case PrepareDownload(id) =>
      val de = getDataEntry(id)
      val newde = de.prepareForDownload
      repository += id -> newde
      CryoEventBus.publish(AttributeChange(s"/cryo/datastore/${id}#status", Some(de.status), newde.status))
      sender ! DownloadPrepared(id)

    case PackData(id, glacierId) =>
      val de = getDataEntry(id)
      val newde = de.pack(glacierId)
      repository += id -> newde
      CryoEventBus.publish(AttributeChange(s"/cryo/datastore/${id}#status", Some(de.status), newde.status))
      sender ! DataPacked(id, glacierId)
      
    case CloseData(id) =>
      val de = getDataEntry(id)
      de.close
      sender ! DataClosed(id)
  }
}

//@Deprecated
//class DatastoreInputStream(cryoctx: CryoContext, id: String, val size: Long = 0, var position: Long = 0) extends InputStream {
//  implicit val contextExecutor = cryoctx.system.dispatcher
//  implicit val timeout = Timeout(10 seconds)
//  import akka.pattern.ask
//
//  private var buffer: ByteIterator = ByteIterator.ByteArrayIterator.empty
//  private var limit = position
//  val lastPosition = position + size
//
//  private def requestMoreData(): Int = {
//    if (limit < lastPosition) {
//      val n = Math.min(1024, (lastPosition - limit))
//      Await.result((cryoctx.datastore ? ReadData(id, position, n.toInt))
//        .map {
//          case DataRead(_, _, data) =>
//            buffer ++= data
//            val dataSize = data.length
//            limit += dataSize
//            dataSize
//          case e: DatastoreError =>
//            throw e
//        }, timeout.duration)
//    } else
//      -1
//  }
//
//  override def available = buffer.size
//
//  override def read: Int = {
//    if (buffer.hasNext) {
//      position += 1
//      buffer.next().toInt & 0xff
//    } else {
//      if (requestMoreData() >= 0)
//        read
//      else
//        -1
//    }
//  }
//
//  override def read(b: Array[Byte], off: Int, len: Int): Int = {
//    val n = math.min(lastPosition - position, len).toInt
//    if (limit - position < n && requestMoreData >= 0)
//      read(b, off, n)
//    else {
//      buffer.copyToArray(b, off, n)
//      n
//    }
//  }
//
//  //override def skip(n: Long): Long = {
//}
//
//@Deprecated
//class DatastoreOutputStream(cryoctx: CryoContext, id: String) extends OutputStream {
//  implicit val contextExecutor = cryoctx.system.dispatcher
//  implicit val timeout = Timeout(10 seconds)
//
//  private var error: Option[CryoError] = None
//
//  override def close = {
//    error.map(e => throw new IOException("DatastoreOutputStream error", e))
//  }
//
//  override def flush = {}
//
//  override def write(b: Array[Byte]) = {
//    error.map(e => throw new IOException("DatastoreOutputStream error", e))
//    (cryoctx.datastore ? WriteData(id, -1, ByteString(b)))
//      .map {
//        case DataWritten(_, _, _) =>
//        case o: Any => error = Some(CryoError(o))
//      }
//  }
//
//  override def write(b: Array[Byte], off: Int, len: Int) = {
//    error.map(e => throw new IOException("DatastoreOutputStream error", e))
//    (cryoctx.datastore ? WriteData(id, -1, ByteString.fromArray(b, off, len)))
//      .map {
//        case DataWritten(_, _, _) =>
//        case o: Any => error = Some(CryoError(o))
//      }
//  }
//
//  override def write(b: Int) = {
//    error.map(e => throw new IOException("DatastoreOutputStream error", e))
//    (cryoctx.datastore ? WriteData(id, -1, ByteString(b)))
//      .map {
//        case DataWritten(_, _, _) =>
//        case o: Any => error = Some(CryoError(o))
//      }
//  }
//}