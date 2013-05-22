package org.rejna.cryo.models

import scala.collection.mutable.{ HashMap, ArrayBuffer }
import scala.collection.JavaConversions.bufferAsJavaList

import akka.actor.Actor
import akka.util.ByteString

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{ FileSystems, Files }
import java.nio.file.StandardOpenOption._
import java.util.UUID
import java.security.MessageDigest

import com.typesafe.config.Config

import com.amazonaws.services.glacier.TreeHashGenerator

import org.rejna.util.MultiRange

case class CreateData(idOption: Option[String], size: Long = 0L)
case class DataCreated(id: String)
case class WriteData(id: String, position: Long, buffer: ByteString)
object WriteData { def apply(id: String, buffer: ByteString): WriteData = WriteData(id, -1, buffer) } // AppendData
case class DataWritten(id: String, position: Long, length: Long)
case class ReadData(id: String, position: Long, length: Int)
case class DataRead(id: String, position: Long, buffer: ByteString)
case class GetDataStatus(id: String)
case class DataStatus(status: EntryStatus.EntryStatus, size: Long, checksum: String)

case class WriteError(message: String) extends Exception(message)
case class OpenError(message: String) extends Exception(message)

object EntryStatus extends Enumeration {
  type EntryStatus = Value
  val Creating, Loading, Created = Value
}

import EntryStatus._
/* serialization :
 * status + size + (status!=Creating ? checksum) + (status==Loading ? range)
 */
abstract class DataEntry(val id: String) extends LoggingClass {
  //val entryAttributeBuilder = attributeBuilder / id
  val storeDirectory = FileSystems.getDefault.getPath("/")
  val file = storeDirectory.resolve(id)

  val statusAttribute: Attribute[EntryStatus]
  def size = sizeAttribute()
  def size_= = sizeAttribute() = _

  val sizeAttribute: Attribute[Long]
  def status = statusAttribute()
  def status_= = statusAttribute() = _

}
class DataEntryCreating(id: String, _size: Long, attributeBuilder: AttributeBuilder) extends DataEntry(id) {
  val statusAttribute = attributeBuilder("status", Creating)
  val sizeAttribute = attributeBuilder("size", _size)

  val digest = MessageDigest.getInstance("SHA-256")
  val MB = 1048576
  if (size > 0 && getFileSize != size)
    throw OpenError("")
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
      throw WriteError("Channel is closed")
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
}

class DataEntryLoading(id: String, _size: Long, checksum: String, attributeBuilder: AttributeBuilder) extends DataEntry(id) {
  val statusAttribute = attributeBuilder("status", Loading)
  val sizeAttribute = attributeBuilder("size", _size)
  val channel = FileChannel.open(file, WRITE, CREATE)
  var range = MultiRange.empty[Long]

  def write(position: Long, buffer: ByteString) = {
    if (position < 0)
      throw WriteError(s"Invalid write position (${position})")
    if (size < position + buffer.length)
      throw WriteError(s"Allocate size is too small (trying to write ${buffer.length} bytes at position ${position} and the size of the store is ${size} bytes)")
    if (!channel.isOpen)
      throw WriteError("Channel is closed")
    val n = channel.write(buffer.asByteBuffer, position)
    range = range | (position, n)
    n
  }

  def close: DataEntryCreated = {
    channel.close
    new DataEntryCreated(id, statusAttribute, sizeAttribute, checksum)
  }
}

class DataStore(config: Config) extends Actor with LoggingClass {
  val attributeBuilder = new AttributeBuilder("/cryo/datastore")
  val data = HashMap.empty[String, DataEntry]
  val storeDirectory = FileSystems.getDefault.getPath(config.getString("cryo.store-directory"))

  def receive: Receive = {
    // TODO try catch
    case CreateData(idOption, size) =>
      val id = idOption.getOrElse {
        var i = ""
        do { i = UUID.randomUUID.toString }
        while (data contains i)
        i
      }
      data += id -> new DataEntryCreating(id, size, attributeBuilder / id)
      sender ! DataCreated(id)
      
    case WriteData(id, position, buffer) =>
      data.get(id).get match {
        case de: DataEntryLoading if position >= 0 =>
          sender ! DataWritten(id, position, de.write(position, buffer))
        case de: DataEntryCreating if position == -1 =>
          sender ! DataWritten(id, position, de.write(buffer))
        case null =>
          sender ! WriteError(s"Store ${id} not found")
        case _ =>
          sender ! WriteError("Invalid data entry state")
      }
      
    case GetDataStatus(id) =>
      data.get(id).get match {
        case null =>
          sender ! WriteError(s"Store ${id} not found")
        case de: DataEntryCreated =>
          sender ! DataStatus(de.status, de.size, de.checksum)
        case de: DataEntry =>
          sender ! DataStatus(de.status, de.size, "")
      }

    case ReadData(id, position, length) =>
      data.get(id).get match {
        case de: DataEntryCreated =>
          sender ! DataRead(id, position, de.read(position, length))
        case null =>
          sender ! WriteError(s"Store ${id} not found")
        case _ =>
          sender ! WriteError("Invalid data entry state")
      }
  }

}