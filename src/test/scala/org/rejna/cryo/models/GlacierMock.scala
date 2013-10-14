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