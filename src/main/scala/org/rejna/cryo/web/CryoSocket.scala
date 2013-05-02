package org.rejna.cryo.web

import akka.actor.Actor

class CryoSocket extends Actor {

  def receive = {
    case Subscribe(subscription) =>
      cryo.eventBus.subscribe(sender, subscription)
    case Unsubscribe(subscription) =>
      cryo.eventBus.unsubscribe(sender, subscription)
    case CreateSnapshot =>
      val snapshot = cryo.newArchive(Index)
      sender ! SnapshotCreated(snapshot.id)
    case GetArchiveList =>
      sender ! ArchiveList(cryo.inventory.archives.values.toList)
    case GetSnapshotList =>
      sender ! SnapshotList(cryo.inventory.snapshots.values.toList)
    case UpdateInventory(maxAge) =>
      cryo.inventory.update(maxAge)
    case GetSnapshotFiles(snapshotId, directory) => {
      val snapshot = cryo.inventory.snapshots(snapshotId)
      val files = snapshot match {
        case ls: LocalSnapshot => ls.files()
        case rs: RemoteSnapshot => rs.remoteFiles.map(_.file.toString)
      }
      val dir = new File(Config.baseDirectory, directory)
      sender ! new SnapshotFiles(snapshotId, directory, getDirectoryContent(dir, files, snapshot.fileFilters)) //fe.toList)
    }
    case UpdateSnapshotFileFilter(snapshotId, directory, filter) =>
      val snapshot = cryo.inventory.snapshots(snapshotId)
      snapshot match {
        case ls: LocalSnapshot => ls.fileFilters(directory) = filter
        case _ => println("UpdateSnapshotFileFilter is valid only for LocalSnapshot")
      }
    case UploadSnapshot(snapshotId) =>
      val snapshot = cryo.inventory.snapshots(snapshotId)
      snapshot match {
        case ls: LocalSnapshot => ls.create
        case _ => println("UpdateSnapshotFileFilter is valid only for LocalSnapshot")
      }
    case msg => println("CryoActor has received an unknown message : " + msg)
  }

  def getDirectoryContent(directory: File, fileSelection: Iterable[String], fileFilters: scala.collection.Map[String, String]) = {
    //println("getDirectoryContent(%s, %s)".format(directory, fileSelection.mkString("(", ",", ")")))
    val dirContent = Option(directory.listFiles).getOrElse(Array[File]())
    dirContent.map(f => {
      val filePath = Config.baseURI.relativize(f.toURI).getPath match {
        case x if x.endsWith("/") => x.substring(0, x.length - 1)
        case x => x
      }
      //println("f=%s; af=%s".format(f, af))
      val (count, size) = ((0, 0L) /: fileSelection)((a, e) =>
        if (e.startsWith(filePath)) (a._1 + 1, a._2 + new File(Config.baseDirectory, e).length) else a)
      //val filePath = Config.baseURI.relativize(f.toURI).getPath
      //println("getDirectoryContent: filePath=%s fileFilters=%s".format(filePath, fileFilters.mkString("(", ",", ")")))
      new FileElement(f, count, size, fileFilters.get('/' + filePath))
    }).toList
  }
}