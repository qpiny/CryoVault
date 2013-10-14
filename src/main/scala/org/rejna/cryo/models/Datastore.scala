package org.rejna.cryo.models

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{ ActorRefFactory }
import akka.util.{ ByteString, ByteIterator, Timeout }

import java.io.{ InputStream, OutputStream, IOException }
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption._
import java.nio.ByteBuffer
import java.util.{ Date, UUID }

import resource.managed
import net.liftweb.json.Serialization

sealed abstract class DatastoreRequest extends Request
sealed abstract class DatastoreResponse extends Response { val id: String }
sealed abstract class DatastoreError(message: String, cause: Throwable) extends CryoError(message, cause)

case class CreateData(idOption: Option[String], description: String, size: Long = 0L) extends DatastoreRequest
case class DataCreated(id: String) extends DatastoreResponse
case class DefineData(id: String, description: String, creationDate: Date, size: Long, checksum: String) extends DatastoreRequest
case class DataDefined(id: String) extends DatastoreResponse
case class WriteData(id: String, position: Long, buffer: ByteString) extends DatastoreRequest
object WriteData { def apply(id: String, buffer: ByteString): WriteData = WriteData(id, -1, buffer) } // AppendData
case class DataWritten(id: String, position: Long, length: Long) extends DatastoreResponse
case class ReadData(id: String, position: Long, length: Int) extends DatastoreRequest
case class DataRead(id: String, position: Long, buffer: ByteString) extends DatastoreResponse
case class CloseData(id: String) extends DatastoreRequest
case class DataClosed(id: String) extends DatastoreResponse
case class GetDataStatus(id: String) extends DatastoreRequest
case class DataStatus(id: String, description: String, creationDate: Date, status: EntryStatus.EntryStatus, size: Long, checksum: String) extends DatastoreResponse

case class OpenError(message: String, cause: Throwable = null) extends DatastoreError(message, cause)
case class WriteError(message: String, cause: Throwable = null) extends DatastoreError(message, cause)
case class ReadError(message: String, cause: Throwable = null) extends DatastoreError(message, cause)
case class DataNotFoundError(id: String, message: String, cause: Throwable = null) extends DatastoreError(message, cause)
case class InvalidDataStatus(message: String, cause: Throwable = null) extends DatastoreError(message, cause)

class Datastore(val cryoctx: CryoContext) extends CryoActor {
  import EntryStatus._

  val attributeBuilder = CryoAttributeBuilder("/cryo/datastore")
  val repository = attributeBuilder.map("repository", Map.empty[String, DataEntry])

  override def postStop = {
    implicit val formats = Json

    try {
      for (channel <- managed(FileChannel.open(cryoctx.workingDirectory.resolve("repository"), WRITE, CREATE))) {
        channel.truncate(0)

        log.debug("Writting repository :")
        repository.values.map { d => log.debug(s"${d.status} | ${d.size} | ${d.id}") }
        val repo = repository.values
          .filter { d => d.status == Created || d.status == Remote }
          .map { _.state }
        channel.write(ByteBuffer.wrap(Serialization.write(repo).getBytes))
      }
    } catch {
      case t: Throwable => println(s"Datastore has failed to store its state", t)
    }
  }

  override def preStart = {
    implicit val formats = Json
    try {
      val source = scala.io.Source.fromFile(cryoctx.workingDirectory.resolve("repository").toFile)
      val repoData = source.getLines mkString "\n"
      source.close()
      val entries = Serialization.read[List[EntryState]](repoData) map {
        case state => DataEntry(cryoctx, attributeBuilder, state)
      }
      repository ++= entries.map(e => e.id -> e)
      log.info("Repository loaded : " + entries.map(_.id).mkString(","))
    } catch {
      case e: IOException => log.warn("Repository file not found")
      case t: Throwable => log.error("Repository load failed", t)
    }
  }

  def receive = cryoReceive {
    case PrepareToDie() => sender ! ReadyToDie()

    case CreateData(idOption, description, size) =>
      val id = idOption.getOrElse {
        var i = ""
        do { i = UUID.randomUUID.toString }
        while (repository contains i)
        i
      } // FIXME ID collision is still possible
      repository.get(id) match {
        case Some(d: DataEntryCreated) =>
          d.close
          repository += id -> new DataEntryCreating(cryoctx, id, description, size, attributeBuilder / id)
          sender ! DataCreated(id)
        case None =>
          repository += id -> new DataEntryCreating(cryoctx, id, description, size, attributeBuilder / id)
          sender ! DataCreated(id)
        case Some(d) =>
          sender ! OpenError(s"Data ${id} can't be created (${d.status})")
      }

    case DefineData(id, description, creationDate, size, checksum) =>
      repository.get(id) match {
        case Some(_) => // data already defined, ignore it
        case None =>
          repository += id -> new DataEntryRemote(cryoctx, id, description, creationDate, size, checksum, attributeBuilder / id)
      }
      sender ! DataDefined(id)

    case WriteData(id, position, buffer) =>
      repository.get(id) match {
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
      repository.get(id) match {
        case None =>
          sender ! DataNotFoundError(id, s"Data ${id} not found")
        case Some(de: DataEntry) =>
          sender ! DataStatus(id, de.description, de.creationDate, de.status, de.size, de.checksum)
      }

    case ReadData(id, position, length) =>
      repository.get(id) match {
        case Some(de: DataEntryCreated) =>
          sender ! DataRead(id, position, de.read(position, length))
        case None =>
          sender ! DataNotFoundError(id, s"Data ${id} not found")
        case Some(de: DataEntry) =>
          sender ! InvalidDataStatus(s"Data ${id}(${de.status}) has invalid status for read")
      }

    case CloseData(id) =>
      repository.get(id) match {
        case Some(de: DataEntryCreating) =>
          val newde = de.close
          repository += id -> newde
          CryoEventBus.publish(AttributeChange(s"/cryo/datastore/${id}#status", Some(EntryStatus.Creating), EntryStatus.Created))
          sender ! DataClosed(id)
        case Some(de: DataEntryLoading) =>
          val newde = de.close
          repository += id -> newde
          CryoEventBus.publish(AttributeChange(s"/cryo/datastore/${id}#status", Some(EntryStatus.Loading), EntryStatus.Created))
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

@Deprecated
class DatastoreInputStream(cryoctx: CryoContext, id: String, val size: Long = 0, var position: Long = 0) extends InputStream {
  implicit val contextExecutor = cryoctx.system.dispatcher
  implicit val timeout = Timeout(10 seconds)
  import akka.pattern.ask

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