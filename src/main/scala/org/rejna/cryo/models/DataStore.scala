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
import akka.event.LoggingReceive

import java.io.{ InputStream, OutputStream, IOException }
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption._
import java.util.UUID
import java.security.MessageDigest

import com.typesafe.config.Config
import org.joda.time.DateTime
import com.amazonaws.services.glacier.TreeHashGenerator

import org.rejna.util.MultiRange

sealed abstract class DataStoreRequest extends Request
sealed abstract class DataStoreResponse extends Response { val id: String }
sealed abstract class DataStoreError(message: String, cause: Throwable) extends CryoError(message, cause)

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
case class DataStatus(id: String, creationDate: DateTime, status: EntryStatus.EntryStatus, size: Long, checksum: String) extends DataStoreResponse

case class OpenError(message: String, cause: Throwable = null) extends DataStoreError(message, cause)
case class WriteError(message: String, cause: Throwable = null) extends DataStoreError(message, cause)
case class ReadError(message: String, cause: Throwable = null) extends DataStoreError(message, cause)
case class DataNotFoundError(id: String, message: String, cause: Throwable = null) extends DataStoreError(message, cause)
case class InvalidDataStatus(message: String, cause: Throwable = null) extends DataStoreError(message, cause)

object EntryStatus extends Enumeration {
  type EntryStatus = Value
  val Creating, Loading, Created = Value
}
class DataStore(cryoctx: CryoContext) extends Actor with LoggingClass {
  import EntryStatus._

  val attributeBuilder = AttributeBuilder("/cryo/datastore")
  val data = attributeBuilder.map("repository", Map.empty[String, DataEntry])

  // serialization : status + size + (status!=Creating ? checksum) + (status==Loading ? range)
  sealed abstract class DataEntry(val id: String, val creationDate: DateTime) extends LoggingClass {
    val file = cryoctx.baseDirectory.resolve(id)

    val statusAttribute: Attribute[EntryStatus]
    def size = sizeAttribute()
    def size_= = sizeAttribute() = _

    val sizeAttribute: Attribute[Long]
    def status = statusAttribute()
    def status_= = statusAttribute() = _
  }

  class DataEntryCreating(id: String, initSize: Long, entryAttributeBuilder: AttributeBuilder) extends DataEntry(id, new DateTime) {
    val statusAttribute = entryAttributeBuilder("status", Creating)
    val sizeAttribute = entryAttributeBuilder("size", initSize)

    val digest = MessageDigest.getInstance("SHA-256")
    val MB = 1024 * 1024
    if (size > 0 && getFileSize != size)
      throw OpenError(s"Data ${id}(Creating) already exists but the expected size doesn't match")
    val channel = FileChannel.open(file, WRITE, APPEND, CREATE)
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
      new DataEntryCreated(id, creationDate, statusAttribute, sizeAttribute, TreeHashGenerator.calculateTreeHash(checksums))
    }
  }
  class DataEntryCreated(id: String, creationDate: DateTime, val statusAttribute: Attribute[EntryStatus], val sizeAttribute: Attribute[Long], val checksum: String) extends DataEntry(id, creationDate) {
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

  class DataEntryLoading(id: String, creationDate: DateTime, initSize: Long, checksum: String, entryAttributeBuilder: AttributeBuilder) extends DataEntry(id, creationDate) {
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
      new DataEntryCreated(id, creationDate, statusAttribute, sizeAttribute, checksum)
    }
  }

  def receive: Receive = LoggingReceive {
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
        case e: Exception => sender ! OpenError(e.getMessage, e)
      }

    case WriteData(id, position, buffer) =>
      try {
        data.get(id) match {
          case Some(de: DataEntryLoading) if position >= 0 =>
            sender ! DataWritten(id, position, de.write(position, buffer))
          case Some(de: DataEntryCreating) if position == -1 =>
            sender ! DataWritten(id, position, de.write(buffer))
          case None =>
            sender ! DataNotFoundError(id, s"Data ${id} not found")
          case Some(de: DataEntry) =>
            sender ! InvalidDataStatus(s"Data ${id}(${de.status}) has invalid status for write")
        }
      } catch {
        case dse: DataStoreError => sender ! dse
        case e: Exception => sender ! WriteError(e.getMessage, e)
      }

    case GetDataStatus(id) =>
      data.get(id) match {
        case None =>
          sender ! DataNotFoundError(id, s"Data ${id} not found")
        case Some(de: DataEntryCreated) =>
          sender ! DataStatus(id, de.creationDate, de.status, de.size, de.checksum)
        case Some(de: DataEntry) =>
          sender ! DataStatus(id, de.creationDate, de.status, de.size, "")
      }

    case ReadData(id, position, length) =>
      try {
        data.get(id) match {
          case Some(de: DataEntryCreated) =>
            sender ! DataRead(id, position, de.read(position, length))
          case None =>
            sender ! DataNotFoundError(id, s"Data ${id} not found")
          case Some(de: DataEntry) =>
            sender ! InvalidDataStatus(s"Data ${id}(${de.status}) has invalid status for read")
        }
      } catch {
        case dse: DataStoreError => sender ! dse
        case e: Exception => sender ! ReadError(e.getMessage, e)
      }

    case CloseData(id) =>
      try {
        data.get(id) match {
          case Some(de: DataEntryCreating) =>
            val newde = de.close
            data += id -> newde
            sender ! DataClosed(id, newde.status, newde.size, newde.checksum)
          case Some(de: DataEntryLoading) =>
            val newde = de.close
            data += id -> newde
            sender ! DataClosed(id, newde.status, newde.size, newde.checksum)
          case Some(de: DataEntryCreated) =>
            sender ! InvalidDataStatus(s"Data ${id}(${de.status}) has invalid status for close")
          case None =>
            sender ! DataNotFoundError(id, s"Data ${id} not found")
        }
      } catch {
        case dse: DataStoreError => sender ! dse
        case e: Exception => sender ! ReadError(e.getMessage, e)
      }
  }
}

class DataStoreInputStream(cryoctx: CryoContext, id: String, val size: Long = 0, var position: Long = 0) extends InputStream {
  implicit val contextExecutor = cryoctx.system.dispatcher
  implicit val timeout = Timeout(10 seconds)

  private var buffer: ByteIterator = ByteIterator.ByteArrayIterator.empty
  private var limit = position
  val lastPosition = position + size

  private def requestMoreData(): Int = {
    if (limit < lastPosition) {
      val n = Math.min(1024, (lastPosition - limit))
      Await.result((cryoctx.datastore ? ReadData(id, position, n.toInt))
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

class DataStoreOutputStream(cryoctx: CryoContext, id: String) extends OutputStream {
  implicit val contextExecutor = cryoctx.system.dispatcher
  implicit val timeout = Timeout(10 seconds)

  private var position = 0
  private var error: Option[DataStoreError] = None

  override def close = {
    error.map(e => throw new IOException("DataStoreOutputStream error", e))
  }

  override def flush = {}

  override def write(b: Array[Byte]) = {
    error.map(e => throw new IOException("DataStoreOutputStream error", e))
    (cryoctx.datastore ? WriteData(id, position, ByteString(b)))
      .map { case e: DataStoreError => error = Some(e) }
    position += b.length
  }

  override def write(b: Array[Byte], off: Int, len: Int) = {
    error.map(e => throw new IOException("DataStoreOutputStream error", e))
    (cryoctx.datastore ? WriteData(id, position, ByteString.fromArray(b, off, len)))
      .map { case e: DataStoreError => error = Some(e) }
    position += len
  }

  override def write(b: Int) = {
    error.map(e => throw new IOException("DataStoreOutputStream error", e))
    (cryoctx.datastore ? WriteData(id, position, ByteString(b)))
      .map { case e: DataStoreError => error = Some(e) }
    position += 1
  }
}