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
import java.util.Date

import akka.util.ByteString

import com.amazonaws.services.glacier.TreeHashGenerator

import org.rejna.util.MultiRange

object EntryStatus extends Enumeration {
  type EntryStatus = Value
  val Creating, Uploading, Cached, Remote, Downloading, Unknown = Value
}
import EntryStatus._

// serialization : status + size + (status!=Creating ? checksum) + (status==Loading ? range)
sealed abstract class DataEntry(
  val cryoctx: CryoContext,
  val id: String,
  val description: String,
  val creationDate: Date,
  val checksum: String) {
  val file = cryoctx.workingDirectory.resolve(id)

  val sizeAttribute: SimpleAttribute[Long]
  def size = sizeAttribute()
  def size_= = sizeAttribute() = _

  def status: EntryStatus

  def state = DataStatus(id, description, creationDate, status, size, checksum)//EntryState(status, id, description, creationDate, size, checksum)
  override def toString = status.toString
}

object DataEntry {
  def apply(cryoctx: CryoContext, attributeBuilder: CryoAttributeBuilder, state: DataStatus/* EntryState */) = {
    val entryAttributeBuilder = attributeBuilder / state.id
    state.status match {
      //case Creating => // not supported
      //case Uploading => // not supported yet
      case Cached =>
        new DataEntryCreated(
          cryoctx,
          state.id,
          state.description,
          state.creationDate,
          entryAttributeBuilder("size", state.size),
          state.checksum)
      case Remote =>
        new DataEntryRemote(
          cryoctx,
          state.id,
          state.description,
          state.creationDate,
          state.size,
          state.checksum,
          entryAttributeBuilder)
      case e =>
        throw new CryoError(s"Unsupported data entry status ${e}")
    }
  }
}

class DataEntryRemote(
  cryoctx: CryoContext,
  id: String,
  description: String,
  creationDate: Date,
  initSize: Long,
  checksum: String,
  entryAttributeBuilder: CryoAttributeBuilder) extends DataEntry(cryoctx, id, description, creationDate, checksum) {

  def status = Remote
  val sizeAttribute = entryAttributeBuilder("size", initSize)

  def prepareForDownload = new DataEntryDownloading(cryoctx, id, description, creationDate, size, checksum, entryAttributeBuilder)
}

class DataEntryCreating(
  cryoctx: CryoContext,
  id: String,
  description: String,
  initSize: Long,
  entryAttributeBuilder: CryoAttributeBuilder) extends DataEntry(cryoctx, id, description, new Date, "") with LoggingClass {

  override val file = cryoctx.workingDirectory.resolve(id + ".creating")
  def status = Creating
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

  def close: DataEntryCreated = {
    channel.close
    log.debug(s"Closing ${id} size = ${Files.size(file)}")
    if (blockSize > 0)
      checksums += digest.digest
    Files.move(file, cryoctx.workingDirectory.resolve(id), REPLACE_EXISTING)
    new DataEntryCreated(cryoctx, id, description, creationDate, sizeAttribute, TreeHashGenerator.calculateTreeHash(checksums))
  }
}

class DataEntryCreated(
  cryoctx: CryoContext,
  id: String,
  description: String,
  creationDate: Date,
  val sizeAttribute: SimpleAttribute[Long],
  checksum: String) extends DataEntry(cryoctx, id, description, creationDate, checksum) with LoggingClass {

  if (size == 0)
    size = Files.size(file)
  val channel = FileChannel.open(file, READ)

  def status = Cached

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

  def close = channel.close
}

class DataEntryDownloading(
  cryoctx: CryoContext,
  id: String,
  description: String,
  creationDate: Date,
  val expectedSize: Long,
  checksum: String,
  entryAttributeBuilder: CryoAttributeBuilder) extends DataEntry(cryoctx, id, description, creationDate, checksum) {

  override val file = cryoctx.workingDirectory.resolve(id + ".loading")
  def status = Downloading
  val sizeAttribute = entryAttributeBuilder("size", 0L)
  val channel = FileChannel.open(file, WRITE, CREATE)
  var range = MultiRange.empty[Long] // TODO Resume

  def write(position: Long, buffer: ByteString) = {
    if (expectedSize < position + buffer.length)
      throw WriteError(s"Allocate size is too small for data ${id}(Loading): trying to write ${buffer.length} bytes at position ${position} and the size of the store is ${size} bytes")
    if (!channel.isOpen)
      throw WriteError(s"Channel of data ${id}(Loading) is closed")
    val n = channel.write(buffer.asByteBuffer, position)
    range = range | (position, n)
    n
  }

  def close: DataEntryCreated = {
    channel.close
    Files.move(file, cryoctx.workingDirectory.resolve(id), REPLACE_EXISTING)
    new DataEntryCreated(cryoctx, id, description, creationDate, sizeAttribute, checksum)
  }

  //override def state = EntryState(status, id, description, creationDate, expectedSize, checksum, Some(range))
}
//case class EntryState(
//  status: EntryStatus,
//  id: String,
//  description: String,
//  creationDate: Date,
//  size: Long,
//  checksum: String)//,
  //range: Option[MultiRange[Long]] = None)