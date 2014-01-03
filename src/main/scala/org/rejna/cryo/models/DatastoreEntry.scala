package org.rejna.cryo.models

import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions.bufferAsJavaList
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption._
import java.nio.file.StandardCopyOption._
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.{ Date, UUID }
import akka.util.ByteString
import com.amazonaws.services.glacier.TreeHashGenerator
import org.rejna.util.MultiRange
import DataType._
import ObjectStatus._
import java.nio.file.CopyOption

// serialization : status + size + (status!=Creating ? checksum) + (status==Loading ? range)
sealed abstract class DataEntry(
  val cryoctx: CryoContext,
  val id: UUID,
  val dataType: DataType,
  val creationDate: Date,
  val checksum: String) {
  val file = cryoctx.workingDirectory.resolve(id.toString)

  val sizeAttribute: SimpleAttribute[Long]
  def size = sizeAttribute()
  def size_= = sizeAttribute() = _

  def status: ObjectStatus
  def glacierId = status.getGlacierId
  def state = DataStatus(id, dataType, creationDate, status, size, checksum)
  override def toString = status.toString

  def read(position: Long, length: Int): ByteString
  def write(position: Long, buffer: ByteString): Int
  def pack(glacierId: String): DataEntry
  def clearLocalCache: DataEntry
  def prepareForDownload: DataEntry
  def close: Unit
}

object DataEntry extends ErrorGenerator {
  def apply(cryoctx: CryoContext, attributeBuilder: CryoAttributeBuilder, state: DataStatus) = {
    val entryAttributeBuilder = attributeBuilder / state.id
    state.status match {
      case Creating() | Uploading() =>
        new DataEntryCreating(cryoctx, state.id, state.dataType, state.size, entryAttributeBuilder("size", state.size))
      case Cached(glacierId) =>
        new DataEntryCreated(cryoctx, state.id, glacierId, state.dataType, state.creationDate, entryAttributeBuilder("size", state.size), state.checksum)
      case Remote(_) | Downloading(_) =>
        new DataEntryRemote(cryoctx, state.id, state.status.getGlacierId.get, state.dataType, state.creationDate, state.size, state.checksum, entryAttributeBuilder("size", state.size))
      case e =>
        throw InvalidState(s"Unsupported data entry status ${e}")
    }
  }
}

class DataEntryRemote(
  cryoctx: CryoContext,
  id: UUID,
  glacierId: String,
  dataType: DataType,
  creationDate: Date,
  initSize: Long,
  checksum: String,
  val sizeAttribute: SimpleAttribute[Long]) extends DataEntry(cryoctx, id, dataType, creationDate, checksum) {

  def status = Remote(glacierId)

  def prepareForDownload = new DataEntryDownloading(cryoctx, id, glacierId, dataType, creationDate, size, checksum, sizeAttribute)

  def read(position: Long, length: Int) = throw InvalidState(s"Data ${id}(${status}) has invalid status for read")
  
  def clearLocalCache = this
  
  def close = Unit
  
  def pack(gid: String) = throw InvalidState(s"Data ${id}(${status}) has invalid status for pack")
  
  def write(position: Long, buffer: ByteString) = throw InvalidState(s"Data ${id}(${status}) has invalid status for write")
}

class DataEntryCreating(
  cryoctx: CryoContext,
  id: UUID,
  dataType: DataType,
  initSize: Long,
  val sizeAttribute: SimpleAttribute[Long]) extends DataEntry(cryoctx, id, dataType, new Date, "") with LoggingClass {

  override val file = cryoctx.workingDirectory.resolve(id + ".creating")
  def status = Creating()

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

  def write(position: Long, buffer: ByteString): Int = {
    if (position != -1)
      throw WriteError("Only append is permitted with creating data entry")
    if (!channel.isOpen)
      throw WriteError(s"Channel of data ${id}(Creating) is closed")
    val array = buffer.toArray
    val n = channel.write(buffer.asByteBuffer)
    size += n.toLong

    var off = 0
    var remaining = n
    var len = Math.min(MB - blockSize, remaining).toInt
    while (remaining > 0) {
      digest.update(array, off, len)
      remaining -= len
      off += len
      blockSize += len
      if (blockSize >= MB) {
        checksums += digest.digest
        digest.reset()
        blockSize -= MB
      }
      len = Math.min(MB, remaining)
    }
    n
  }

  def pack(glacierId: String): DataEntryCreated = {
    channel.close
    log.debug(s"Closing ${id} size = ${Files.size(file)}")
    if (blockSize > 0)
      checksums += digest.digest
    Files.move(file, file.resolveSibling(id.toString), REPLACE_EXISTING)
    new DataEntryCreated(cryoctx, id, glacierId, dataType, creationDate, sizeAttribute, TreeHashGenerator.calculateTreeHash(checksums))
  }
  
  def clearLocalCache = throw InvalidState(s"Data ${id}(${status}) has invalid status for clearLocalCache")
  
  def close = if (channel.isOpen) channel.close
  
  def prepareForDownload = throw InvalidState(s"Data ${id}(${status}) has invalid status for prepareForDownload")
  
  def read(position: Long, length: Int) = throw InvalidState(s"Data ${id}(${status}) has invalid status for read")
}

class DataEntryCreated(
  cryoctx: CryoContext,
  id: UUID,
  glacierId: String,
  dataType: DataType,
  creationDate: Date,
  val sizeAttribute: SimpleAttribute[Long],
  checksum: String) extends DataEntry(cryoctx, id, dataType, creationDate, checksum) with LoggingClass {

  if (size == 0)
    size = Files.size(file)
  val channel = FileChannel.open(file, READ)

  def status = Cached(glacierId)

  def read(position: Long, length: Int) = {
    val buffer = ByteBuffer.allocate(length)
    channel.position(position)
    val n = channel.read(buffer)
    log.debug(s"${n} bytes read (${length} requested) from ${id} at position ${position}")
    log.debug(s"buffer = ${buffer}")
    buffer.flip
    val c = ByteString(buffer)
    log.debug(s"buffer = ${c}")
    c
  }

  def write(position: Long, buffer: ByteString): Int = throw InvalidState(s"Data ${id}(${status}) has invalid status for write")

  def close = channel.close
  
  def prepareForDownload = new DataEntryDownloading(cryoctx, id, glacierId, dataType, creationDate, size, checksum, sizeAttribute)
  
  def clearLocalCache = new DataEntryRemote(cryoctx, id, glacierId, dataType, creationDate, size, checksum, sizeAttribute)
  
  def pack(gid: String) = throw InvalidState(s"Data ${id}(${status}) has invalid status for pack")
  
}

class DataEntryDownloading(
  cryoctx: CryoContext,
  id: UUID,
  glacierId: String,
  dataType: DataType,
  creationDate: Date,
  val expectedSize: Long,
  checksum: String,
  val sizeAttribute: SimpleAttribute[Long]) extends DataEntry(cryoctx, id, dataType, creationDate, checksum) {

  override val file = cryoctx.workingDirectory.resolve(id + ".loading")
  def status = Downloading(glacierId)
  val channel = FileChannel.open(file, WRITE, CREATE)
  var range = MultiRange.empty[Long] // TODO Resume

  def write(position: Long, buffer: ByteString): Int = {
    if (expectedSize < position + buffer.length)
      throw WriteError(s"Allocate size is too small for data ${id}(Loading): trying to write ${buffer.length} bytes at position ${position} and the size of the store is ${size} bytes")
    if (!channel.isOpen)
      throw WriteError(s"Channel of data ${id}(Loading) is closed")
    val n = channel.write(buffer.asByteBuffer, position)
    range = range | (position, n)
    n
  }

  def close = {
    channel.close
  }

  def prepareForDownload = throw InvalidState(s"Data ${id}(${status}) has invalid status for prepareForDownload")
  
  def clearLocalCache = throw InvalidState(s"Data ${id}(${status}) has invalid status for clearLocalCache")
  
  def read(position: Long, length: Int) = throw InvalidState(s"Data ${id}(${status}) has invalid status for read")
  
  def pack(glacierId: String): DataEntryCreated = {
    channel.close
    new DataEntryCreated(cryoctx, id, glacierId, dataType, creationDate, sizeAttribute, checksum)
  }
}
