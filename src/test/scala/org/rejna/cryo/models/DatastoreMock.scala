package org.rejna.cryo.models

import scala.collection.mutable.Map

import akka.actor.Actor
import akka.util.ByteString

import java.util.{ UUID, Date }

case class DataEntryMock(id: String, description: String, creationDate: Date, size: Long, checksum: String, content: ByteString) {
  def write(buffer: ByteString) =
    DataEntryMock(id, description, creationDate, content.size + buffer.size, checksum, content.concat(buffer))

  def read(position: Long, length: Int) =
    content.slice(position.toInt, position.toInt + length)
}

class DatastoreMock(cryoctx: CryoContext) extends Actor {
  
  val repository = Map.empty[String, DataEntryMock]
  
  def receive = {
    case PrepareToDie() => sender ! ReadyToDie()

    case CreateData(idOption, description, size) =>
      val id = idOption.getOrElse {
        var i = ""
        do { i = UUID.randomUUID.toString }
        while (repository contains i)
        i
      }
      repository += id -> DataEntryMock(id, description, new Date, size, "", ByteString.empty)
      sender ! DataCreated(id)

    case DefineData(id, description, creationDate, size, checksum) =>
      repository.get(id) match {
        case Some(_) => // data already defined, ignore it
        case None =>
          repository += id -> DataEntryMock(id, description, new Date, size, checksum, ByteString.empty)
      }
      sender ! DataDefined(id)

    case WriteData(id, position, buffer) => // append only
      repository.get(id) match {
        case Some(de) =>
          repository += id -> de.write(buffer)
          sender ! DataWritten(id, position, buffer.size)
        case None =>
          sender ! DataNotFoundError(id, s"Data ${id} not found")
      }

    case GetDataStatus(id) =>
      repository.get(id) match {
        case None =>
          sender ! DataNotFoundError(id, s"Data ${id} not found")
        case Some(de) =>
          sender ! DataStatus(id, de.description, de.creationDate, EntryStatus.Creating, de.size, de.checksum)
      }

    case ReadData(id, position, length) =>
      repository.get(id) match {
        case Some(de) =>
          sender ! DataRead(id, position, de.read(position, length))
        case None =>
          sender ! DataNotFoundError(id, s"Data ${id} not found")
      }

    case CloseData(id) =>
      repository.get(id) match {
        case Some(de) =>
          CryoEventBus.publish(AttributeChange(s"/cryo/datastore/${id}#status", Some(EntryStatus.Creating), EntryStatus.Created))
          sender ! DataClosed(id)
        case None =>
          sender ! DataNotFoundError(id, s"Data ${id} not found")
      }
  }
}