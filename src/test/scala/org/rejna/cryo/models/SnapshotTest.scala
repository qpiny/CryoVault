package org.rejna.cryo.models

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ TestKit, TestActorRef, ImplicitSender }
import com.typesafe.config.ConfigFactory

import org.scalatest.matchers.MustMatchers
import org.scalatest.{ FlatSpecLike, BeforeAndAfter }

import java.io.File

class SnapshotTest extends TestKit(ActorSystem())
  with ImplicitSender
  with FlatSpecLike
  with BeforeAndAfter {

  val cryoctx = new CryoContext(system, ConfigFactory.load())
  val snapshotBuilderRef = TestActorRef[SnapshotBuilder](Props(classOf[SnapshotBuilder], cryoctx, "random-id"))
  val snapshotBuilder = snapshotBuilderRef.underlyingActor

  before {
    snapshotBuilder.fileFilters.clear
    snapshotBuilder.size = 0
  }

  "Snapshot actor" must "return its ID" in {
    snapshotBuilderRef ! GetID()
    expectMsg(ID("random-id"))
  }

  it must "update file filter" in {
    snapshotBuilderRef ! SnapshotUpdateFilter("random-id", ".", ExtensionFilter("txt"))
    expectMsg(FilterUpdated())
    assert(snapshotBuilder.fileFilters.size == 1)
    assert(snapshotBuilder.files.size == 3)
  }

  it should "return an exception if one tries to attempt directory traversal" in {
    snapshotBuilderRef ! SnapshotUpdateFilter("random-id", "../..", ExtensionFilter("txt"))
    expectMsg(DirectoryTraversalError(s"..${File.separator}.."))
  }
  it must "list files in base directory updated with filter" in {
    snapshotBuilder.fileFilters += cryoctx.filesystem.getPath("") -> ExtensionFilter("txt")
    val path = cryoctx.baseDirectory.resolve("folder1")
    snapshotBuilderRef ! SnapshotGetFiles("random-files", path.toString)
    expectMsg(SnapshotFiles("random-files", path.toString,
      FileElement(cryoctx.baseDirectory.relativize(path.resolve("another.file")), false, None, 0, 0) ::
        FileElement(cryoctx.baseDirectory.relativize(path.resolve("folder11")), true, None, 1, 4) ::
        FileElement(cryoctx.baseDirectory.relativize(path.resolve("folder12")), true, None, 2, 35) ::
        Nil))
  }

  it must "list files with filter" in {
    snapshotBuilder.fileFilters += cryoctx.filesystem.getPath("folder1/folder12") -> ExtensionFilter("txt")
    val path = cryoctx.baseDirectory.resolve("folder1")
    snapshotBuilderRef ! SnapshotGetFiles("random-files", path.toString)
    expectMsg(SnapshotFiles("random-files", path.toString,
        FileElement(cryoctx.baseDirectory.relativize(path.resolve("another.file")), false, None, 0, 0) ::
        FileElement(cryoctx.baseDirectory.relativize(path.resolve("folder11")), true, None, 0, 0) ::
        FileElement(cryoctx.baseDirectory.relativize(path.resolve("folder12")), true, Some(ExtensionFilter("txt")), 2, 35) ::
        Nil))
  }
  
  // TODO SnapshotUpload / SnapshotUploaded
}
  