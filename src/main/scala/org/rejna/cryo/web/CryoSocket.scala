package org.rejna.cryo.web

import scala.util.matching.Regex
import akka.actor.Actor
import java.io.File
import org.rejna.cryo.models.{ Cryo, ArchiveType, LocalSnapshot, RemoteSnapshot, Config }
import akka.event.EventBus
import akka.event.SubchannelClassification
import akka.util.Subclassification
import net.liftweb.json._
import org.mashupbots.socko.events.WebSocketFrameEvent

import org.rejna.cryo.models.{ Cryo, ArchiveType }

object EventSerialization {
  implicit object CryoFormats extends DefaultFormats {
    override val typeHintFieldName = "type"
  }

  case class EventSender(wsFrame: WebSocketFrameEvent) {
    def write(event: Event) = wsFrame.writeText(Serialization.write(event))
  }

  implicit def toEventSender(wsFrame: WebSocketFrameEvent) = EventSender(wsFrame)
}

object CryoSocketBus extends EventBus with SubchannelClassification {
  import EventSerialization._

  type Event = org.rejna.cryo.web.ResponseEvent
  type Classifier = String
  type Subscriber = WebSocketFrameEvent

  protected def classify(event: ResponseEvent) = event.path
  protected def subclassification = new Subclassification[Classifier] {
    def isEqual(x: Classifier, y: Classifier) = x == y
    def isSubclass(x: Classifier, y: Classifier) = x.startsWith(y)
  }

  // FIXME add synchronized + volatile
  protected def publish(event: Event, subscriber: Subscriber): Unit = {
    if (!ignore(subscriber).exists(_.findFirstIn(event.path).isDefined))
      subscriber.write(event)
  }

  private var ignore = Map[Subscriber, Set[Regex]]()
  def addIgnoreSubscription(subscriber: Subscriber, subscription: String) = {
    ignore = ignore.updated(subscriber,
      ignore.getOrElse(subscriber, Set[Regex]()) + subscription.r)
  }

  def removeIgnoreSubscription(subscriber: Subscriber, subscription: String) = {
    ignore = ignore.updated(subscriber,
      ignore.getOrElse(subscriber, Set[Regex]()) - subscription.r)
  }
}

class CryoSocket extends Actor {
  import EventSerialization._

  def receive = {
    case wsFrame: WebSocketFrameEvent =>
      val json = parse(wsFrame.readText)
      val event = json.extract[RequestEvent]
      event match {
        case Subscribe(subscription) =>
          CryoSocketBus.subscribe(wsFrame, subscription)
        case Unsubscribe(subscription) =>
          CryoSocketBus.unsubscribe(wsFrame, subscription)
        case AddIgnoreSubscription(subscription) =>
          CryoSocketBus.addIgnoreSubscription(wsFrame, subscription)
        case RemoveIgnoreSubscription(subscription) =>

        case CreateSnapshot() =>
          val snapshot = Cryo.newArchive(ArchiveType.Index)
          wsFrame.write(SnapshotCreated(snapshot.id))
        case GetArchiveList() =>
          wsFrame.write(ArchiveList(Cryo.inventory.archives.values.toList))
        case GetSnapshotList() =>
          wsFrame.write(SnapshotList(Cryo.inventory.snapshots.values.toList))
        case UpdateInventory(maxAge) =>
          Cryo.inventory.update(maxAge)
        case GetSnapshotFiles(snapshotId, directory) => {
          val snapshot = Cryo.inventory.snapshots(snapshotId)
          val files = snapshot match {
            case ls: LocalSnapshot => ls.files()
            case rs: RemoteSnapshot => rs.remoteFiles.map(_.file.toString)
          }
          val dir = new File(Config.baseDirectory, directory)
          wsFrame.write(new SnapshotFiles(snapshotId, directory, getDirectoryContent(dir, files, snapshot.fileFilters))) //fe.toList)
        }
        case UpdateSnapshotFileFilter(snapshotId, directory, filter) =>
          val snapshot = Cryo.inventory.snapshots(snapshotId)
          snapshot match {
            case ls: LocalSnapshot => ls.fileFilters(directory) = filter
            case _ => println("UpdateSnapshotFileFilter is valid only for LocalSnapshot")
          }
        case UploadSnapshot(snapshotId) =>
          val snapshot = Cryo.inventory.snapshots(snapshotId)
          snapshot match {
            case ls: LocalSnapshot => ls.create
            case _ => println("UpdateSnapshotFileFilter is valid only for LocalSnapshot")
          }
        case msg => println("CryoActor has received an unknown message : " + msg)
      }

  }
  /*
    case Subscribe(subscription) =>
      Cryo.eventBus.subscribe(sender, subscription)
    case Unsubscribe(subscription) =>
      Cryo.eventBus.unsubscribe(sender, subscription)
    case CreateSnapshot =>
      val snapshot = Cryo.newArchive(ArchiveType.Index)
      sender ! SnapshotCreated(snapshot.id)
    case GetArchiveList =>
      sender ! ArchiveList(Cryo.inventory.archives.values.toList)
    case GetSnapshotList =>
      sender ! SnapshotList(Cryo.inventory.snapshots.values.toList)
    case RefreshInventory(maxAge) =>
      Cryo.inventory.update(maxAge)
    case GetSnapshotFiles(snapshotId, directory) => {
      val snapshot = Cryo.inventory.snapshots(snapshotId)
      val files = snapshot match {
        case ls: LocalSnapshot => ls.files()
        case rs: RemoteSnapshot => rs.remoteFiles.map(_.file.toString)
      }
      val dir = new File(Config.baseDirectory, directory)
      sender ! new SnapshotFiles(snapshotId, directory, getDirectoryContent(dir, files, snapshot.fileFilters)) //fe.toList)
    }
    case UpdateSnapshotFileFilter(snapshotId, directory, filter) =>
      val snapshot = Cryo.inventory.snapshots(snapshotId)
      snapshot match {
        case ls: LocalSnapshot => ls.fileFilters(directory) = filter
        case _ => println("UpdateSnapshotFileFilter is valid only for LocalSnapshot")
      }
    case UploadSnapshot(snapshotId) =>
      val snapshot = Cryo.inventory.snapshots(snapshotId)
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
  */
}
