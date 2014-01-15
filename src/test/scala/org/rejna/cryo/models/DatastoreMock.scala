package org.rejna.cryo.models

import scala.collection.mutable.Map

import akka.actor.{ Actor, Stash }
import akka.util.ByteString

import java.util.{ UUID, Date }

import DataType._
import DataStatus._

case class DataEntryMock(id: UUID, glacierId: Option[String], dataType: DataType, creationDate: Date, status: ObjectStatus, size: Long, checksum: String, content: ByteString) {
  def write(buffer: ByteString) = copy(content = content.concat(buffer))

  def read(position: Long, length: Int) =
    content.slice(position.toInt, position.toInt + length)
}

case class AddDataMock(entry: DataEntryMock)
case class DataAddedMock(id: UUID)

class DatastoreMock(_cryoctx: CryoContext) extends CryoActor(_cryoctx) with Stash {

  val repository = Map.empty[UUID, DataEntryMock]

  def receive = cryoReceive {
    case MakeActorReady =>
      unstashAll()
      context.become(receiveWhenReady)
    case AddDataMock(entry) =>
      repository += entry.id -> entry
      sender ! DataAddedMock(entry.id)
    case _ =>
      stash()
  }

  def receiveWhenReady: Receive = cryoReceive {
    case PrepareToDie() => sender ! ReadyToDie()

    case AddDataMock(entry) =>
      repository += entry.id -> entry
      sender ! DataAddedMock(entry.id)

    case CreateData(idOption, dataType, size) =>
      val id = idOption.getOrElse {

        var i = UUID.randomUUID
        while (repository contains i) {
          i = UUID.randomUUID
        }
        i
      }
      repository += id -> DataEntryMock(id, None, dataType, new Date, Writable, size, "", ByteString.empty)
      sender ! Created(id)

    case DefineData(id, glacierId, dataType, creationDate, size, checksum) =>
      repository.get(id) match {
        case Some(_) => // data already defined, ignore it
        case None =>
          repository += id -> DataEntryMock(id, Some(glacierId), dataType, new Date, Remote, size, checksum, ByteString.empty)
      }
      sender ! DataDefined(id)

    case DeleteData(id) =>
      repository.remove(id) match {
        case Some(e) =>
          sender ! Deleted(id)
        case None =>
          sender ! NotFoundError(s"Data ${id} not found")
      }

    case WriteData(id, position, buffer) => // append only
      repository.get(id) match {
        case Some(de) =>
          repository += id -> de.write(buffer)
          sender ! DataWritten(id, position, buffer.size)
        case None =>
          sender ! NotFound(Left(id), s"Data ${id} not found")
      }

    case GetDataEntry(Left(id)) =>
      repository.get(id) match {
        case None =>
          sender ! NotFound(Left(id), s"Data ${id} not found")
        case Some(de) =>
          log.info(s"Send data status to ${sender}]")
          sender ! DataEntry(id, de.glacierId, de.dataType, de.creationDate, de.status, de.size, de.checksum)
      }

    case ReadData(id, position, length) =>
      repository.get(id) match {
        case Some(de) =>
          sender ! DataRead(id, position, de.read(position, length))
        case None =>
          sender ! NotFound(Left(id), s"Data ${id} not found")
      }

    case ClearLocalCache(id) =>
      repository.get(id) match {
        case Some(de) if de.status == Readable =>
          repository += id -> de.copy(status = Remote)
          CryoEventBus.publish(AttributeChange(s"/cryo/datastore/${id}#status", Some(Readable), Remote))
          sender ! LocalCacheCleared(id)
        case Some(_) =>
          throw InvalidState("")
        case None =>
          sender ! NotFound(Left(id), s"Data ${id} not found")
      }

    case PrepareDownload(id) =>
      repository.get(id) match {
        case Some(de) if de.status == Remote =>
          repository += id -> de.copy(status = Writable)
          CryoEventBus.publish(AttributeChange(s"/cryo/datastore/${id}#status", Some(Remote), Writable))
          sender ! DownloadPrepared(id)
        case Some(_) =>
          throw InvalidState("")
        case None =>
          sender ! NotFound(Left(id), s"Data ${id} not found")
      }

    case PackData(id, glacierId) =>
      repository.get(id) match {
        case Some(de) if de.status == Writable =>
          repository += id -> de.copy(status = Readable)
          CryoEventBus.publish(AttributeChange(s"/cryo/datastore/${id}#status", Some(Writable), Readable))
          sender ! DataPacked(id, glacierId)
        case Some(_) =>
          throw InvalidState("")
        case None =>
          sender ! NotFound(Left(id), s"Data ${id} not found")
      }

    case CloseData(id) =>
      repository.get(id) match {
        case Some(de) =>
          sender ! DataClosed(id)
        case None =>
          sender ! NotFound(Left(id), s"Data ${id} not found")
      }
  }
}