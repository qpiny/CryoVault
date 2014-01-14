package org.rejna.cryo.models

import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions.bufferAsJavaList
import scala.util.Try

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

import ObjectStatus._
import DataType._

// serialization : status + size + (status!=Creating ? checksum) + (status==Loading ? range)
class DataItem(
  val cryoctx: CryoContext,
  val attributeBuilder: CryoAttributeBuilder,
  val id: UUID,
  val _glacierId: Option[String] = None,
  val dataType: DataType,
  val creationDate: Date = new Date,
  _status: ObjectStatus = Writable,
  _size: Long = 0L,
  _checksum: String = "") extends LoggingClass {

  val file = cryoctx.workingDirectory.resolve(id.toString)
  val filePart = cryoctx.workingDirectory.resolve(id.toString + ".part")

  implicit val logSource = getClass.getName

  val sizeAttribute = attributeBuilder("size", 0L)
  def size = sizeAttribute()
  def size_= = sizeAttribute() = _

  private val statusAttribute = attributeBuilder("status", _status)
  def status = statusAttribute()
  private def status_= = statusAttribute() = _

  private val glacierIdAttribute = attributeBuilder("glacierId", _glacierId)
  def glacierId = glacierIdAttribute()
  private def glacierId_= = glacierIdAttribute() = _

  def checksum = Try(TreeHashGenerator.calculateTreeHash(checksums)).getOrElse(_checksum)

  def dataEntry = DataEntry(id, glacierId, dataType, creationDate, status, size, checksum)

  val digest = MessageDigest.getInstance(cryoctx.hashAlgorithm)
  val MB = 1024 * 1024
  var blockSize = 0
  val checksums = ArrayBuffer.empty[Array[Byte]]

  var channel: Option[FileChannel] = None
  def initChannel = {
    if (channel.isEmpty) {
      status match {
        case Readable =>
          channel = Some(FileChannel.open(file, READ))
        case Writable =>
          val c = FileChannel.open(filePart, WRITE, APPEND, CREATE)
          c.truncate(0)
          channel = Some(c)
        case Remote =>
          channel = None
      }
    }
  }
  
  def getChannel = {
    initChannel
    channel.getOrElse(throw InvalidState(""))
  }

  override def toString = status.toString

  def read(position: Long, length: Int): ByteString = {
    if (status != Readable)
      throw InvalidState("")
    val buffer = ByteBuffer.allocate(length)
    getChannel.position(position)
    val n = getChannel.read(buffer)
    log.debug(s"${n} bytes read (${length} requested) from ${id} at position ${position}")
    log.debug(s"buffer = ${buffer}")
    buffer.flip
    ByteString(buffer)
  }

  def write(position: Long, buffer: ByteString): Int = {
    if (status != Writable)
      throw InvalidState(s"")
    val array = buffer.toArray
    val n = getChannel.write(buffer.asByteBuffer)
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

  def close() = {
    channel.map(_.close())
  }

  def setRemote = {
    if (status != Readable) throw InvalidState("")
    close()
    Files.deleteIfExists(file)
    Files.deleteIfExists(filePart)
    status = Remote
    channel = None
  }

  def setWritable = {
    if (status != Remote) throw InvalidState("")
    status = Writable
  }

  def setReadable = {
    if (status != Writable) throw InvalidState("")
    close()
    Files.move(filePart, file, REPLACE_EXISTING)
    status = Readable
  }
}
