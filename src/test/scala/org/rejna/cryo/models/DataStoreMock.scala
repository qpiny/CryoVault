package org.rejna.cryo.models

import akka.actor.Actor

import java.util.UUID

class DataStoreMock(cryoctx: CryoContext, respository: Map[String, String]) extends Datastore(cryoctx) {
	def receive = cryoReceive {
    case PrepareToDie() => sender ! ReadyToDie()

    case CreateData(idOption, description, size) =>
      val id = idOption.getOrElse {
        var i = ""
        do { i = UUID.randomUUID.toString }
        while (repository contains i)
        i
      }
      sender ! DataCreated(id)

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