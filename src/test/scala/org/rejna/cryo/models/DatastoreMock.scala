package org.rejna.cryo.models

import scala.collection.mutable.Map

import akka.actor.{ Actor, Stash }
import akka.util.ByteString

import java.util.{ UUID, Date }

import DataType._
import ObjectStatus._

case class DataEntryMock(id: UUID, dataType: DataType, creationDate: Date, size: Long, checksum: String, content: ByteString) {
  def write(buffer: ByteString) =
    DataEntryMock(id, dataType, creationDate, content.size + buffer.size, checksum, content.concat(buffer))

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
      repository += id -> DataEntryMock(id, dataType, new Date, size, "", ByteString.empty)
      sender ! DataCreated(id)

    case DefineData(id, glacierId, dataType, creationDate, size, checksum) =>
      repository.get(id) match {
        case Some(_) => // data already defined, ignore it
        case None =>
          repository += id -> DataEntryMock(id, dataType, new Date, size, checksum, ByteString.empty)
      }
      sender ! DataDefined(id)

    case WriteData(id, position, buffer) => // append only
      repository.get(id) match {
        case Some(de) =>
          repository += id -> de.write(buffer)
          sender ! DataWritten(id, position, buffer.size)
        case None =>
          sender ! DataNotFoundError(Left(id), s"Data ${id} not found")
      }

    case GetDataStatus(Left(id)) =>
      repository.get(id) match {
        case None =>
          sender ! DataNotFoundError(Left(id), s"Data ${id} not found")
        case Some(de) =>
          log.info(s"Send data status to ${sender}]")
          sender ! DataStatus(id, de.dataType, de.creationDate, Creating(), de.size, de.checksum)
      }

    case ReadData(id, position, length) =>
      repository.get(id) match {
        case Some(de) =>
          sender ! DataRead(id, position, de.read(position, length))
        case None =>
          sender ! DataNotFoundError(Left(id), s"Data ${id} not found")
      }

    case CloseData(id) =>
      repository.get(id) match {
        case Some(de) =>
          CryoEventBus.publish(AttributeChange(s"/cryo/datastore/${id}#status", Some(Creating), Cached))
          sender ! DataClosed(id)
        case None =>
          sender ! DataNotFoundError(Left(id), s"Data ${id} not found")
      }
  }
}