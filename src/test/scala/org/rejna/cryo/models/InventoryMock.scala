package org.rejna.cryo.models

import akka.actor.Actor

import java.util.Date

class InventoryMock(cryoctx: CryoContext) extends Actor {
  def receive = {
    case CreateArchive() =>
      sender ! ArchiveCreated("NewlyCreatedArchiveId")
    case CreateSnapshot() =>
      sender ! SnapshotCreated("NewlyCreatedSnapshotId")
    case GetArchiveList() =>
      sender ! ArchiveList(new Date, InventoryStatus.Cached, List.empty[DataStatus])
    case GetSnapshotList() =>
      sender ! SnapshotList(new Date, InventoryStatus.Cached, List.empty[DataStatus])
  }
}