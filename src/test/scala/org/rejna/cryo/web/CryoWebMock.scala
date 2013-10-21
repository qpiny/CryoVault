package org.rejna.cryo.web

import akka.util.ByteString
import java.util.Date
import org.rejna.cryo.models._
import akka.pattern.ask


object CryoWebMock extends App {
  override def main(args: Array[String]) = {
    // start CryoWeb but in test context (with test configuration)
//    val inventory = CryoWeb.cryoctx.inventory
//    inventory ! AddSnapshotMock(DataEntryMock(
//        "snapshot_1",
//        "description of snapshot 1",
//        new Date,
//        123,
//        "checksum of snapshot 1",
//        ByteString("content of snapshot 1")))
    implicit val timeout = CryoWeb.cryoctx.getTimeout(classOf[UpdateJobList])
    (CryoWeb.cryoctx.manager ? UpdateJobList(List.empty[Job]))
    CryoWeb.cryoctx.sendToAll(MakeActorReady)
    CryoWeb.main(args)
  }
}