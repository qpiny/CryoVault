package org.rejna.cryo.models

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ TestKit, TestActorRef, ImplicitSender }
import com.typesafe.config.ConfigFactory

import org.scalatest.matchers.MustMatchers
import org.scalatest.{ FlatSpecLike, BeforeAndAfter }

class SnapshotTest extends TestKit(ActorSystem())
  with ImplicitSender
  with FlatSpecLike
  with BeforeAndAfter {

  val config = ConfigFactory.load()
  val cryoctx = new CryoContext(system, config)
  //val  = system.actorOf(Props(classOf[SnapshotBuilder], cryoctx, "random-id"))
  val snapshotBuilderRef = TestActorRef[SnapshotBuilder](Props(classOf[SnapshotBuilder], cryoctx, "random-id"))
  val snapshotBuilder = snapshotBuilderRef.underlyingActor
  //case class SnapshotGetFiles(id: String, path: String) extends SnapshotRequest
  //case class SnapshotFiles(id: String, path: String, files: List[FileElement])
  //case class FileElement(name: Path, isFolder: Boolean, filter: Option[FileFilter], count: Int, size: Long)
  ////case class FileElement(file: Path, count: Int, size: Long, filter: Option[FileFilter])
  ////new FileElement(f, fileSize.size, fileSize.sum, fileFilters.get(filePath.toString.replace(java.io.File.separatorChar, '/')))
  //case class FilterUpdated() extends SnapshotResponse
  //case class SnapshotUpload(id: String) extends SnapshotRequest
  //case class SnapshotUploaded(id: String) extends SnapshotResponse
  //case class GetID() extends Request
  //case class ID(id: String) extends SnapshotResponse
  //
  ////case class ArchiveCreated(id: String) extends SnapshotResponse
  ////case object CreateSnapshot extends SnapshotRequest
  ////case class SnapshotCreated(aref: ActorRef) extends SnapshotResponse
  //
  //class SnapshotBuilder(val cryoctx: CryoContext, id: String) extends CryoActor {

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
    expectMsg(CryoError(""))
  }

  it should "throw an exception if one tries to attempt directory traversal" in {
    snapshotBuilderRef ! SnapshotUpdateFilter("random-id", "../..", ExtensionFilter("txt"))
    
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
    snapshotBuilder.fileFilters += cryoctx.baseDirectory.resolve("folder1/folder12") -> ExtensionFilter("txt")
    val path = cryoctx.baseDirectory.resolve("folder1")
    snapshotBuilderRef ! SnapshotGetFiles("random-files", path.toString)
    expectMsg(SnapshotFiles("random-files", path.toString,
        FileElement(cryoctx.baseDirectory.relativize(path.resolve("another.file")), false, None, 0, 0) ::
        FileElement(cryoctx.baseDirectory.relativize(path.resolve("folder11")), true, None, 0, 0) ::
        FileElement(cryoctx.baseDirectory.relativize(path.resolve("folder12")), true, None, 2, 35) ::
        Nil))
  }
}
  