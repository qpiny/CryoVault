package org.rejna.cryo.models

import akka.actor.{ Actor, Stash }

import java.util.Date

class GlacierMock(_cryoctx: CryoContext) extends CryoActor(_cryoctx) with CryoAskSupport {

  def receive = {
    case MakeActorReady =>
    case PrepareToDie() =>
      sender ! ReadyToDie()
    case RefreshJobList() =>
      sender ! Done()
    case RefreshInventory() =>
      sender ! JobRequested(
        Job("RefreshInventoryJobId",
          "Inventory refresh request job",
          new Date,
          InProgress(),
          None,
          "inventory"))
    case DownloadArchive(archiveId: String) =>
      sender ! JobRequested(
        Job(
          "DownloadArchiveJobId",
          "Archive download request job",
          new Date,
          InProgress(),
          None,
          archiveId))
    case Upload(id, dataType) =>
      (cryoctx.datastore ? PackData(id, id.toString))
        .emap(s"Fail to pack data ${id}", {
          case DataPacked(_, _) => Uploaded(id)
        }).reply("Fail to upload data", sender)
  }

}