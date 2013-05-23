package org.rejna.cryo.models

import scala.collection.mutable.{ HashMap, ArrayBuffer }
import scala.collection.JavaConversions.bufferAsJavaList
import scala.annotation.tailrec
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{ Actor, ActorRefFactory }
import akka.pattern.ask
import akka.util.{ ByteString, ByteIterator, Timeout }

import java.io.{ InputStream, OutputStream, IOException }
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{ FileSystems, Files }
import java.nio.file.StandardOpenOption._
import java.util.UUID
import java.security.MessageDigest

import com.typesafe.config.Config

import com.amazonaws.services.glacier.TreeHashGenerator

import org.rejna.util.MultiRange

sealed abstract class DataStoreRequest
sealed abstract class DataStoreResponse
sealed abstract class DataStoreError(message: String, cause: Option[Throwable]) extends Exception(message, cause.get)

case class CreateData(idOption: Option[String], size: Long = 0L) extends DataStoreRequest
case class DataCreated(id: String) extends DataStoreResponse
case class WriteData(id: String, position: Long, buffer: ByteString) extends DataStoreRequest
object WriteData { def apply(id: String, buffer: ByteString): WriteData = WriteData(id, -1, buffer) } // AppendData
case class DataWritten(id: String, position: Long, length: Long) extends DataStoreResponse
case class ReadData(id: String, position: Long, length: Int) extends DataStoreRequest
case class DataRead(id: String, position: Long, buffer: ByteString) extends DataStoreResponse
case class CloseData(id: String) extends DataStoreRequest
case class DataClosed(id: String, status: EntryStatus.EntryStatus, size: Long, checksum: String) extends DataStoreResponse
case class GetDataStatus(id: String) extends DataStoreRequest
case class DataStatus(id: String, status: EntryStatus.EntryStatus, size: Long, checksum: String) extends DataStoreResponse

case class OpenError(message: String, cause: Option[Throwable] = None) extends DataStoreError(message, cause)
case class WriteError(message: String, cause: Option[Throwable] = None) extends DataStoreError(message, cause)
case class ReadError(message: String, cause: Option[Throwable] = None) extends DataStoreError(message, cause)
case class DataNotFoundError(message: String, cause: Option[Throwable] = None) extends DataStoreError(message, cause)
case class InvalidDataStatus(message: String, cause: Option[Throwable] = None) extends DataStoreError(message, cause)

object EntryStatus extends Enumeration {
  type EntryStatus = Value
  val Creating, Loading, Created = Value
}
class DataStore(config: Config) extends Actor with LoggingClass {
  import EntryStatus._

  val attributeBuilder = new AttributeBuilder("/cryo/datastore")
  val data = HashMap.empty[String, DataEntry]
  val storeDirectory = FileSystems.getDefault.getPath(config.getString("cryo.store-directory"))

  // serialization : status + size + (status!=Creating ? checksum) + (status==Loading ? range)
  abstract class DataEntry(val id: String) extends LoggingClass {
    val file = storeDirectory.resolve(id)

    val statusAttribute: Attribute[EntryStatus]
    def size = sizeAttribute()
    def size_= = sizeAttribute() = _

    val sizeAttribute: Attribute[Long]
    def status = statusAttribute()
    def status_= = statusAttribute() = _
  }

  class DataEntryCreating(id: String, initSize: Long, entryAttributeBuilder: AttributeBuilder) extends DataEntry(id) {
    val statusAttribute = entryAttributeBuilder("status", Creating)
    val sizeAttribute = entryAttributeBuilder("size", initSize)

    val digest = MessageDigest.getInstance("SHA-256")
    val MB = 1024 * 1024
    if (size > 0 && getFileSize != size)
      throw OpenError(s"Data ${id}(Creating) already exists but the expected size doesn't match")
    val channel = FileChannel.open(storeDirectory.resolve(id), WRITE, APPEND, CREATE)
    if (size == 0)
      channel.truncate(0)
    var blockSize = 0
    val checksums = ArrayBuffer.empty[Array[Byte]]

    private def getFileSize =
      try Files.size(file)
      catch { case e: IOException => 0 }

    def write(buffer: ByteString): Int = {
      if (!channel.isOpen)
        throw WriteError(s"Channel of data ${id}(Creating) is closed")
      val array = buffer.toArray
      val n = channel.write(buffer.asByteBuffer)
      size += n.toLong

      var off = 0
      var remaining = n
      var len = Math.min(MB - (size % MB), remaining).toInt
      while (remaining > 0) {
        digest.update(array, off, len)
        remaining -= len
        off += len
        blockSize += len
        if (blockSize >= MB) {
          checksums += digest.digest
          digest.reset()
          blockSize = 0
        }
        len = Math.min(MB, remaining)
      }
      n
    }

    def close: DataEntryCreated = {
      channel.close
      new DataEntryCreated(id, statusAttribute, sizeAttribute, TreeHashGenerator.calculateTreeHash(checksums))
    }
  }
  class DataEntryCreated(id: String, statusAttribute: Attribute[EntryStatus], sizeAttribute: Attribute[Long], val checksum: String) extends DataEntry(id) {
    status = Created

    val channel = FileChannel.open(file, READ)

    def read(position: Long, length: Int) = {
      val buffer = ByteBuffer.allocate(length)
      channel.position(position)
      channel.read(buffer)
      ByteString(buffer)
    }

    def close = channel.close
  }

  class DataEntryLoading(id: String, initSize: Long, checksum: String, entryAttributeBuilder: AttributeBuilder) extends DataEntry(id) {
    val statusAttribute = entryAttributeBuilder("status", Loading)
    val sizeAttribute = entryAttributeBuilder("size", initSize)
    val channel = FileChannel.open(file, WRITE, CREATE)
    var range = MultiRange.empty[Long] // TODO Resume

    def write(position: Long, buffer: ByteString) = {
      if (size < position + buffer.length)
        throw WriteError(s"Allocate size is too small for data ${id}(Loading): trying to write ${buffer.length} bytes at position ${position} and the size of the store is ${size} bytes")
      if (!channel.isOpen)
        throw WriteError(s"Channel of data ${id}(Loading) is closed")
      val n = channel.write(buffer.asByteBuffer, position)
      range = range | (position, n)
      n
    }

    def close: DataEntryCreated = {
      channel.close
      new DataEntryCreated(id, statusAttribute, sizeAttribute, checksum)
    }
  }

  def receive: Receive = {
    case CreateData(idOption, size) =>
      try {
        val id = idOption.getOrElse {
          var i = ""
          do { i = UUID.randomUUID.toString }
          while (data contains i)
          i
        }
        data += id -> new DataEntryCreating(id, size, attributeBuilder / id)
        sender ! DataCreated(id)
      } catch {
        case dse: DataStoreError => sender ! dse
        case e: Exception => sender ! OpenError(e.getMessage, Some(e))
      }

    case WriteData(id, position, buffer) =>
      try {
        data.get(id).get match {
          case de: DataEntryLoading if position >= 0 =>
            sender ! DataWritten(id, position, de.write(position, buffer))
          case de: DataEntryCreating if position == -1 =>
            sender ! DataWritten(id, position, de.write(buffer))
          case null =>
            sender ! DataNotFoundError(s"Data ${id} not found")
          case de: DataEntry =>
            sender ! InvalidDataStatus(s"Data ${id}(${de.status}) has invalid status for write")
        }
      } catch {
        case dse: DataStoreError => sender ! dse
        case e: Exception => sender ! WriteError(e.getMessage, Some(e))
      }

    case GetDataStatus(id) =>
      data.get(id).get match {
        case null =>
          sender ! DataNotFoundError(s"Data ${id} not found")
        case de: DataEntryCreated =>
          sender ! DataStatus(id, de.status, de.size, de.checksum)
        case de: DataEntry =>
          sender ! DataStatus(id, de.status, de.size, "")
      }

    case ReadData(id, position, length) =>
      try {
        data.get(id).get match {
          case de: DataEntryCreated =>
            sender ! DataRead(id, position, de.read(position, length))
          case null =>
            sender ! DataNotFoundError(s"Data ${id} not found")
          case de: DataEntry =>
            sender ! InvalidDataStatus(s"Data ${id}(${de.status}) has invalid status for read")
        }
      } catch {
        case dse: DataStoreError => sender ! dse
        case e: Exception => sender ! ReadError(e.getMessage, Some(e))
      }

    case CloseData(id) =>
      try {
        data.get(id).get match {
          case de: DataEntryCreating =>
            val newde = de.close
            data += id -> newde
            sender ! DataClosed(id, newde.status, newde.size, newde.checksum)
          case de: DataEntryLoading =>
            val newde = de.close
            data += id -> newde
            sender ! DataClosed(id, newde.status, newde.size, newde.checksum)
          case null =>
            sender ! DataNotFoundError(s"Data ${id} not found")
        }
      } catch {
        case dse: DataStoreError => sender ! dse
        case e: Exception => sender ! ReadError(e.getMessage, Some(e))
      }
  }
}

class DataStoreInputStream(id: String, val size: Long = 0, var position: Long = 0)(implicit system: ActorRefFactory) extends InputStream {
  implicit val contextExecutor = system.dispatcher
  implicit val timeout = Timeout(10 seconds)
  val datastore = system.actorFor("/user/datastore")

  private var buffer: ByteIterator = ByteIterator.ByteArrayIterator.empty
  private var limit = position
  val lastPosition = position + size

  private def requestMoreData(): Int = {
    if (limit < lastPosition) {
      val n = Math.min(1024, (lastPosition - limit))
      Await.result((datastore ? ReadData(id, position, n.toInt))
        .map {
          case DataRead(_, _, data) =>
            buffer ++= data
            val dataSize = data.length
            limit += dataSize
            dataSize
          case e: DataStoreError =>
            throw e
        }, timeout.duration)
    } else
      -1
  }

  override def available = buffer.size

  @tailrec
  override def read: Int = {
    if (buffer.hasNext) {
      position += 1
      buffer.next().toInt & 0xff
    } else {
      if (requestMoreData() >= 0)
        read
      else
        -1
    }
  }

  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    val n = math.min(lastPosition - position, len).toInt
    if (limit - position < n && requestMoreData >= 0)
      read(b, off, n)
    else {
      buffer.copyToArray(b, off, n)
      n
    }
  }

  //override def skip(n: Long): Long = {
}

class DataStoreOutputStream(id: String)(implicit val system: ActorRefFactory) extends OutputStream {
  implicit val contextExecutor = system.dispatcher
  implicit val timeout = Timeout(10 seconds)
  val datastore = system.actorFor("/user/datastore")

  private var position = 0
  private var error: Option[DataStoreError] = None

  def close = {
    error.map(e => throw new IOException("DataStoreOutputStream error", e))
  }

  def flush = {}

  def write(b: Array[Byte]) = {
    error.map(e => throw new IOException("DataStoreOutputStream error", e))
    (datastore ? WriteData(id, position, ByteString(b)))
      .map { case e: DataStoreError => error = Some(e) }
    position += b.length
  }

  def write(b: Array[Byte], off: Int, len: Int) = {
    error.map(e => throw new IOException("DataStoreOutputStream error", e))
    (datastore ? WriteData(id, position, ByteString.fromArray(b, off, len)))
      .map { case e: DataStoreError => error = Some(e) }
    position += len
  }

  def write(b: Int) = {
    error.map(e => throw new IOException("DataStoreOutputStream error", e))
    (datastore ? WriteData(id, position, ByteString(b)))
      .map { case e: DataStoreError => error = Some(e) }
    position += 1
  }
}