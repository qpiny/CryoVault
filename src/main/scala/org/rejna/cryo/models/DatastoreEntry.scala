package org.rejna.cryo.models

import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions.bufferAsJavaList

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption._
import java.nio.channels.FileChannel
import java.security.MessageDigest

import akka.util.ByteString

import org.joda.time.DateTime

import com.amazonaws.services.glacier.TreeHashGenerator

import org.rejna.util.MultiRange

object EntryStatus extends Enumeration {
  type EntryStatus = Value
  val Creating, Loading, Created, Remote = Value
}
import EntryStatus._

// serialization : status + size + (status!=Creating ? checksum) + (status==Loading ? range)
sealed abstract class DataEntry(
  val cryoctx: CryoContext,
  val id: String,
  val description: String,
  val creationDate: DateTime) {
  val file = cryoctx.workingDirectory.resolve(id)

  val statusAttribute: Attribute[EntryStatus]
  def size = sizeAttribute()
  def size_= = sizeAttribute() = _

  val sizeAttribute: Attribute[Long]
  def status = statusAttribute()
  def status_= = statusAttribute() = _
}

class DataEntryRemote(
  cryoctx: CryoContext,
  id: String,
  description: String,
  creationDate: DateTime,
  initSize: Long,
  val checksum: String,
  entryAttributeBuilder: AttributeBuilder) extends DataEntry(cryoctx, id, description, creationDate) {

  val statusAttribute = entryAttributeBuilder("status", Remote)
  val sizeAttribute = entryAttributeBuilder("size", initSize)

  def prepareForDownload = new DataEntryLoading(cryoctx, id, description, creationDate, size, checksum, entryAttributeBuilder)
}

class DataEntryCreating(
  cryoctx: CryoContext,
  id: String,
  description: String,
  initSize: Long,
  entryAttributeBuilder: AttributeBuilder) extends DataEntry(cryoctx, id, description, new DateTime) {

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
    if (blockSize > 0)
      checksums += digest.digest
    new DataEntryCreated(cryoctx, id, description, creationDate, statusAttribute, sizeAttribute, TreeHashGenerator.calculateTreeHash(checksums))
  }
}

class DataEntryCreated(
    cryoctx: CryoContext,
    id: String,
    description: String,
    creationDate: DateTime,
    val statusAttribute: Attribute[EntryStatus],
    val sizeAttribute: Attribute[Long],
    val checksum: String) extends DataEntry(cryoctx, id, description, creationDate) {
  
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

class DataEntryLoading(
    cryoctx: CryoContext,
    id: String,
    description: String,
    creationDate: DateTime,
    val expectedSize: Long,
    checksum: String,
    entryAttributeBuilder: AttributeBuilder) extends DataEntry(cryoctx, id, description, creationDate) {
  
  val statusAttribute = entryAttributeBuilder("status", Loading)
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
    new DataEntryCreated(cryoctx, id, description, creationDate, statusAttribute, sizeAttribute, checksum)
  }
}