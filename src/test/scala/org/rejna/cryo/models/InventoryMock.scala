package org.rejna.cryo.models

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

import akka.actor.{ Actor, Stash }

import java.util.{ Date, UUID }

import ObjectStatus._

case class AddSnapshotMock(snapshot: DataEntryMock)
case class SnapshotAddedMock(snapshotId: UUID)
case class AddArchiveMock(archive: DataEntryMock)
case class ArchiveAddedMock(archiveId: UUID)

class InventoryMock(_cryoctx: CryoContext) extends CryoActor(_cryoctx) with Stash {

  val snapshots = ListBuffer.empty[UUID]
  val archives = ListBuffer.empty[UUID]

  def receive = {
    case MakeActorReady =>
      unstashAll()
      context.become(receiveWhenReady)
    case AddSnapshotMock(snapshot) =>
      val _sender = sender
      snapshots += snapshot.id
      cryoctx.datastore ? AddDataMock(snapshot) map {
        case DataAddedMock(id) => _sender ! SnapshotAddedMock(id)
        case e: Any => _sender ! CryoError("Error while creating a new snapshot", e)
      }
    case AddArchiveMock(archive) =>
      val _sender = sender
      archives += archive.id
      cryoctx.datastore ? AddDataMock(archive) map {
        case DataAddedMock(id) => _sender ! ArchiveAddedMock(id)
        case e: Any => _sender ! CryoError("Error while creating a new archive", e)
      }
    case _ =>
      stash()
  }

  def receiveWhenReady: Receive = cryoReceive {
    case AddSnapshotMock(snapshot) =>
      val _sender = sender
      snapshots += snapshot.id
      cryoctx.datastore ? AddDataMock(snapshot) map {
        case DataAddedMock(id) => _sender ! SnapshotAddedMock(id)
        case e: Any => _sender ! CryoError("Error while creating a new snapshot", e)
      }
    case AddArchiveMock(archive) =>
      val _sender = sender
      archives += archive.id
      cryoctx.datastore ? AddDataMock(archive) map {
        case DataAddedMock(id) => _sender ! ArchiveAddedMock(id)
        case e: Any => _sender ! CryoError("Error while creating a new archive", e)
      }
    case CreateArchive() =>
      sender ! ArchiveCreated(UUID.randomUUID())
    case CreateSnapshot() =>
      sender ! SnapshotCreated(UUID.randomUUID())
    case GetArchiveList() =>
      val _sender = sender
      Future.sequence(archives.map(cryoctx.datastore ? GetDataStatus(_))) map {
        case dsl =>
          val archiveList = dsl.filter(_.isInstanceOf[DataStatus]).toList.asInstanceOf[List[DataStatus]]
          _sender ! ArchiveList(new Date,
            Cached(UUID.randomUUID().toString()),
            archiveList)
      } onFailure {
        case e: Throwable => _sender ! CryoError("Error while retrieving archive list", e)
      }
    case GetSnapshotList() =>
      val _sender = sender
      Future.sequence(snapshots.map(cryoctx.datastore ? GetDataStatus(_))) map {
        case dsl =>
          val snapshotList = dsl.filter(_.isInstanceOf[DataStatus]).toList.asInstanceOf[List[DataStatus]]
          _sender ! SnapshotList(new Date,
            Cached(UUID.randomUUID().toString),
            snapshotList)
      } onFailure {
        case e: Throwable => _sender ! CryoError("Error while retrieving snapshot list", e)
      }
  }
}