package org.rejna.cryo.models

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

import akka.actor.{ Actor, Stash }

import java.util.Date

case class AddSnapshotMock(snapshot: DataEntryMock)
case class SnapshotAddedMock(snapshotId: String)
case class AddArchiveMock(archive: DataEntryMock)
case class ArchiveAddedMock(archiveId: String)

class InventoryMock(val cryoctx: CryoContext) extends CryoActor with Stash {

  val snapshots = ListBuffer.empty[String]
  val archives = ListBuffer.empty[String]

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

  def receiveWhenReady: Receive = {
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
      sender ! ArchiveCreated("NewlyCreatedArchiveId")
    case CreateSnapshot() =>
      sender ! SnapshotCreated("NewlyCreatedSnapshotId")
    case GetArchiveList() =>
      Future.sequence(archives.map(cryoctx.datastore ? GetDataStatus(_))) map {
        case dsl => sender ! ArchiveList(new Date,
          InventoryStatus.Cached,
          dsl.filter(_.isInstanceOf[DataStatus]).asInstanceOf[List[DataStatus]])
      }
    case GetSnapshotList() =>
      Future.sequence(snapshots.map(cryoctx.datastore ? GetDataStatus(_))) map {
        case dsl => sender ! SnapshotList(new Date,
          InventoryStatus.Cached,
          dsl.filter(_.isInstanceOf[DataStatus]).asInstanceOf[List[DataStatus]])
      }
  }
}