package org.rejna.cryo.web

import akka.util.ByteString

import java.util.Date

import org.rejna.cryo.models._

object CryoWebMock extends App {
  override def main(args: Array[String]) = {
    // start CryoWeb but in test context (with test configuration)
    val inventory = CryoWeb.cryoctx.inventory
    inventory ! AddSnapshotMock(DataEntryMock(
        "snapshot_1",
        "description of snapshot 1",
        new Date,
        123,
        "checksum of snapshot 1",
        ByteString("content of snapshot 1")))
    MakeCryoContextReady(CryoWeb.cryoctx)
    CryoWeb.main(args)
  }
}