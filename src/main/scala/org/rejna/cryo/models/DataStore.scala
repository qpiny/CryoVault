package org.rejna.cryo.models

import scala.collection.mutable.HashMap

import akka.actor.Actor
import akka.util.ByteString

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.StandardOpenOption._

import com.typesafe.config.Config

case class CreateData(id: String, size: Long)
case class WriteData(id: String, position: Long, buffer: ByteString)
object WriteData { def apply(id: String, buffer: ByteString): WriteData = WriteData(id, -1, buffer) } // AppendData
case class DataWritten(id: String, position: Long, length: Long)
case class ReadData(id: String, position: Long, length: Long)
case class DataRead(id: String, position: Long, buffer: ByteString)
case class GetDataStatus(id: String)
case class DataStatus(status: EntryStatus.EntryStatus, size: Long)

case class WriteError(message: String) extends Exception(message)

object EntryStatus extends Enumeration {
  type EntryStatus = Value
  val Creating, Downloading, Created, NonExistent = Value
}
class DataStore(config: Config) extends Actor {
  val attributeBuilder = new AttributeBuilder("/cryo/datastore")
  val data = HashMap.empty[String, DataEntry]
  val storeDirectory = FileSystems.getDefault.getPath(config.getString("cryo.store-directory"))

  import EntryStatus._
  class DataEntry(val id: String) {
    val entryAttributeBuilder = attributeBuilder / id
    private val sizeAttribute = entryAttributeBuilder("size", 0L)
    def size = sizeAttribute()
    def size_= = sizeAttribute() = _
    var range: (Long, Long) = (0L, 0L) // TODO : multirange + attribute 
    private val statusAttribute = entryAttributeBuilder("status", Creating)
    def status = statusAttribute()
    def status_= = statusAttribute() = _
    var channel: Option[FileChannel] = None

    val oneByteBuffer = ByteBuffer.allocate(1).put(0.toByte)

    private def getOrCreateChannel = {
      channel.getOrElse {
        val fc = FileChannel.open(storeDirectory.resolve(id), READ, CREATE)
        channel = Some(fc)
        fc
      }
    }

    def create(size: Long) = {
      val fc = getOrCreateChannel
      fc.truncate(size)
      if (size > 0) {
        fc.write(oneByteBuffer, size - 1)
        status = Downloading
      } else {
        status = Creating
      }
      this.size = size
      range = (0L, 0L)
    }

    def write(position: Long, buffer: ByteString) = {
      if (position >= 0 && size < position + buffer.length)
        throw WriteError(s"Allocate size is too small (trying to write ${buffer.length} bytes at position ${position} and the size of the store is ${size} bytes)")
      val c = channel.getOrElse { throw WriteError(s"One can't write into closed store") }
      status match {
        case Downloading =>
          if (position < 0)
            throw WriteError(s"Invalid write position (${position})")
          c.write(buffer.asByteBuffer, position)
        case Creating =>
          c.write(buffer.asByteBuffer)
        case _ =>
          throw WriteError(s"One can't write into store with the state ${status}")
      }
      // TODO range += (position, buffer.length)
      // if range is full then close channel and state=Created
    }

    def read(position: Long, length: Long) = {
      val buffer = ByteBuffer.allocate(length.toInt)
      if (status != Created)
        throw WriteError(s"One can't read into store with the state ${status}")
      val c = getOrCreateChannel
      c.read(buffer, position)
      buffer
    }

    def close = channel.map(_.close())
  }

  def receive: Receive = {
    // TODO try catch
    case CreateData(id, size) =>
      data.getOrElseUpdate(id, new DataEntry(id))
        .create(size)
    case WriteData(id, position, buffer) =>
      data.get(id) match {
        case Some(de) =>
          de.write(position, buffer)
        // send DataWritten to sender
        case None =>
          sender ! WriteError(s"Store ${id} not found")
      }
    case GetDataStatus(id) =>
      data.get(id) match {
        case None => sender ! DataStatus(NonExistent, 0)
        case Some(de) => sender ! DataStatus(de.status, de.size)
      }

    case ReadData(id, position, length) =>

      data.get(id) match {
        case Some(de) =>
          val buffer = de.read(position, length)
          sender ! DataRead(id, position, ByteString(buffer))
        case None =>
          sender ! WriteError(s"Store ${id} not found")
      }
  }

}