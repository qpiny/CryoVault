package org.rejna.cryo.models

import akka.actor.Actor

import java.util.Date

class GlacierMock(cryoctx: CryoContext) extends Actor {
  def receive = {
    case RefreshJobList() =>
      sender ! JobListRefreshed()
    case RefreshInventory() =>
      sender ! RefreshInventoryRequested(
        InventoryJob("RefreshInventoryJobId",
          "Inventory refresh request job",
          new Date,
          InProgress("in progress"),
          None))
    case DownloadArchive(archiveId: String) =>
      sender ! DownloadArchiveRequested(
        ArchiveJob(
          "DownloadArchiveJobId",
          "Archive download request job",
          new Date,
          InProgress("in progress"),
          None,
          archiveId))
    case UploadData(id: String) =>
      sender ! DataUploaded(id: String)

  }

}