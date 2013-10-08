package org.rejna.cryo.models

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ TestKit, TestActorRef, ImplicitSender }

import com.typesafe.config.ConfigFactory

import scala.concurrent.Promise
import scala.concurrent.duration._

import org.scalatest.matchers.MustMatchers
import org.scalatest.{ FlatSpecLike, BeforeAndAfter }

import java.util.Date

class InventoryTest extends TestKit(ActorSystem())
  with ImplicitSender
  with FlatSpecLike
  with BeforeAndAfter {

  val cryoctx = new CryoContext(system, ConfigFactory.load())
  val inventoryRef = TestActorRef[Inventory](Props(classOf[Inventory], cryoctx))
  val inventory = inventoryRef.underlyingActor

  //  case class UpdateInventoryDate(date: Date) extends InventoryInternalMessage
  //case class AddArchive(id: String) extends InventoryInternalMessage
  //case class AddSnapshot(id: String) extends InventoryInternalMessage
  //case class CreateArchive() extends InventoryRequest
  //case class ArchiveCreated(id: String) extends InventoryResponse
  //case class CreateSnapshot() extends InventoryRequest
  //case class SnapshotCreated(id: String) extends InventoryResponse
  //case class GetArchiveList() extends InventoryRequest
  //case class ArchiveList(date: Date, status: InventoryStatus, archives: List[DataStatus]) extends InventoryResponse
  //
  //case class GetSnapshotList() extends InventoryRequest
  //case class SnapshotList(date: Date, status: InventoryStatus, snapshots: List[DataStatus]) extends InventoryResponse
  //case class SnapshotNotFound(id: String, message: String, cause: Throwable = null) extends InventoryError(message, cause)
  //
  //case class InventoryEntry(id: String, description: String, creationDate: Date, size: Long, checksum: String)
  //case class InventoryMessage(date: Date, entries: List[InventoryEntry])

}