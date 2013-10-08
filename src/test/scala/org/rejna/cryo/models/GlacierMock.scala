package org.rejna.cryo.models

import akka.actor.{ Actor, Stash }

import java.util.Date

class GlacierMock(cryoctx: CryoContext) extends Actor with Stash {
  
  def receive = {
    case MakeActorReady =>
      unstashAll()
      context.become(receiveWhenReady)
    case _ =>
      stash()
  }
  
  def receiveWhenReady: Receive = {
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