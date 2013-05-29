package org.rejna.cryo.models

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{ Actor, ActorRefFactory }
import akka.pattern.ask
import akka.util.{ ByteString, ByteIterator, Timeout }

import java.io.{ InputStream, OutputStream, IOException }
import java.util.UUID

import org.joda.time.DateTime

sealed abstract class DatastoreRequest extends Request
sealed abstract class DatastoreResponse extends Response { val id: String }
sealed abstract class DatastoreError(message: String, cause: Throwable) extends CryoError(message, cause)

case class CreateData(idOption: Option[String], description: String, size: Long = 0L) extends DatastoreRequest
case class DataCreated(id: String) extends DatastoreResponse
case class DefineData(id: String, description: String, creationDate: DateTime, size: Long, checksum: String) extends DatastoreRequest
case class DataDefined(id: String) extends DatastoreResponse
case class WriteData(id: String, position: Long, buffer: ByteString) extends DatastoreRequest
object WriteData { def apply(id: String, buffer: ByteString): WriteData = WriteData(id, -1, buffer) } // AppendData
case class DataWritten(id: String, position: Long, length: Long) extends DatastoreResponse
case class ReadData(id: String, position: Long, length: Int) extends DatastoreRequest
case class DataRead(id: String, position: Long, buffer: ByteString) extends DatastoreResponse
case class CloseData(id: String) extends DatastoreRequest
case class DataClosed(id: String) extends DatastoreResponse
case class GetDataStatus(id: String) extends DatastoreRequest
case class DataStatus(id: String, description: String, creationDate: DateTime, status: EntryStatus.EntryStatus, size: Long, checksum: String) extends DatastoreResponse

case class OpenError(message: String, cause: Throwable = null) extends DatastoreError(message, cause)
case class WriteError(message: String, cause: Throwable = null) extends DatastoreError(message, cause)
case class ReadError(message: String, cause: Throwable = null) extends DatastoreError(message, cause)
case class DataNotFoundError(id: String, message: String, cause: Throwable = null) extends DatastoreError(message, cause)
case class InvalidDataStatus(message: String, cause: Throwable = null) extends DatastoreError(message, cause)

class Datastore(cryoctx: CryoContext) extends Actor with LoggingClass {
  import EntryStatus._

  val attributeBuilder = AttributeBuilder("/cryo/datastore")
  val data = attributeBuilder.map("repository", Map.empty[String, DataEntry])

  def receive: Receive = CryoReceive {
    case CreateData(idOption, description, size) =>
      val id = idOption.getOrElse {
        var i = ""
        do { i = UUID.randomUUID.toString }
        while (data contains i)
        i
      }
      data += id -> new DataEntryCreating(cryoctx, id, description, size, attributeBuilder / id)
      sender ! DataCreated(id)

    case DefineData(id, description, creationDate, size, checksum) =>
      data.get(id) match {
        case Some(_) => // data already defined, ignore it
        case None =>
          data += id -> new DataEntryRemote(cryoctx, id, description, creationDate, size, checksum, attributeBuilder / id)
      }
      sender ! DataDefined(id)

    case WriteData(id, position, buffer) =>
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

    case GetDataStatus(id) =>
      data.get(id) match {
        case None =>
          sender ! DataNotFoundError(id, s"Data ${id} not found")
        case Some(de: DataEntryCreated) =>
          sender ! DataStatus(id, de.description, de.creationDate, de.status, de.size, de.checksum)
        case Some(de: DataEntry) =>
          sender ! DataStatus(id, de.description, de.creationDate, de.status, de.size, "")
      }

    case ReadData(id, position, length) =>
      data.get(id) match {
        case Some(de: DataEntryCreated) =>
          sender ! DataRead(id, position, de.read(position, length))
        case None =>
          sender ! DataNotFoundError(id, s"Data ${id} not found")
        case Some(de: DataEntry) =>
          sender ! InvalidDataStatus(s"Data ${id}(${de.status}) has invalid status for read")
      }

    case CloseData(id) =>
      data.get(id) match {
        case Some(de: DataEntryCreating) =>
          val newde = de.close
          data += id -> newde
          sender ! DataClosed(id)
        case Some(de: DataEntryLoading) =>
          val newde = de.close
          data += id -> newde
          sender ! DataClosed(id)
        case Some(de: DataEntryCreated) =>
          sender ! InvalidDataStatus(s"Data ${id}(${de.status}) has invalid status for close")
        case Some(de: DataEntryRemote) =>
          sender ! InvalidDataStatus(s"Data ${id}(${de.status}) has invalid status for close")
        case None =>
          sender ! DataNotFoundError(id, s"Data ${id} not found")
      }
  }
}

class DatastoreInputStream(cryoctx: CryoContext, id: String, val size: Long = 0, var position: Long = 0) extends InputStream {
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
          case e: DatastoreError =>
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

class DatastoreOutputStream(cryoctx: CryoContext, id: String) extends OutputStream {
  implicit val contextExecutor = cryoctx.system.dispatcher
  implicit val timeout = Timeout(10 seconds)

  private var error: Option[CryoError] = None

  override def close = {
    error.map(e => throw new IOException("DatastoreOutputStream error", e))
  }

  override def flush = {}

  override def write(b: Array[Byte]) = {
    error.map(e => throw new IOException("DatastoreOutputStream error", e))
    (cryoctx.datastore ? WriteData(id, -1, ByteString(b)))
      .map {
        case DataWritten(_, _, _) =>
        case o: Any => error = Some(CryoError(o))
      }
  }

  override def write(b: Array[Byte], off: Int, len: Int) = {
    error.map(e => throw new IOException("DatastoreOutputStream error", e))
    (cryoctx.datastore ? WriteData(id, -1, ByteString.fromArray(b, off, len)))
      .map {
        case DataWritten(_, _, _) =>
        case o: Any => error = Some(CryoError(o))
      }
  }

  override def write(b: Int) = {
    error.map(e => throw new IOException("DatastoreOutputStream error", e))
    (cryoctx.datastore ? WriteData(id, -1, ByteString(b)))
      .map {
        case DataWritten(_, _, _) =>
        case o: Any => error = Some(CryoError(o))
      }
  }
}