package org.rejna.cryo.models

import akka.actor.{ Actor, Stash }

import java.util.Date

class GlacierMock(cryoctx: CryoContext) extends Actor {
  
  def receive = {
    case MakeActorReady =>
    case PrepareToDie() =>
      sender ! ReadyToDie()
    case RefreshJobList() =>
      sender ! JobListRefreshed()
    case RefreshInventory() =>
      sender ! RefreshInventoryRequested(
        Job("RefreshInventoryJobId",
          "Inventory refresh request job",
          new Date,
          InProgress(),
          None,
          "inventory"))
    case DownloadArchive(archiveId: String) =>
      sender ! DownloadArchiveRequested(
        Job(
          "DownloadArchiveJobId",
          "Archive download request job",
          new Date,
          InProgress(),
          None,
          archiveId))
    case UploadData(id: String) =>
      sender ! DataUploaded(id: String)
  }

}