package org.rejna.cryo.web

import scala.util.matching.Regex
import scala.concurrent.duration._
import akka.actor.Actor
import java.io.File
import org.rejna.cryo.models.{ Cryo, ArchiveType, LocalSnapshot, RemoteSnapshot, Config, CryoEventBus }
import akka.event.EventBus
import akka.event.SubchannelClassification
import akka.util.Subclassification
import net.liftweb.json._
import org.mashupbots.socko.events.WebSocketFrameEvent
import org.rejna.cryo.models.{ Cryo, ArchiveType, AttributeChange, AttributeListChange, Event }

object EventTypeHints extends TypeHints {
  val hints =
    classOf[UploadSnapshot] ::
      classOf[UpdateSnapshotFileFilter] ::
      classOf[Unsubscribe] ::
      classOf[Subscribe] ::
      classOf[RemoveIgnoreSubscription] ::
      classOf[RefreshInventory] ::
      classOf[GetSnapshotList] ::
      classOf[GetSnapshotFiles] ::
      classOf[GetArchiveList] ::
      classOf[CreateSnapshot] ::
      classOf[SnapshotCreated] ::
      classOf[AddIgnoreSubscription] ::
      classOf[ArchiveList] ::
      classOf[SnapshotList] ::
      classOf[AddFile] ::
      classOf[ArchiveCreation] ::
      classOf[SnapshotFiles] ::
      classOf[AttributeChange[_]] ::
      classOf[AttributeListChange[_]] ::
      Nil

  def hintFor(clazz: Class[_]) = clazz.getSimpleName
  def classFor(hint: String) = hints find (hintFor(_) == hint)
}
object EventSerialization {
  implicit object CryoFormats extends DefaultFormats {
    override val typeHintFieldName = "type"
    override val typeHints = EventTypeHints
    override val customSerializers =
      JsonSerializer ::
        Nil
  }

  case class EventSender(wsFrame: WebSocketFrameEvent) {
    def write(event: Event) = wsFrame.writeText(Serialization.write(event))
  }

  implicit def toEventSender(wsFrame: WebSocketFrameEvent) = EventSender(wsFrame)
}

object CryoSocketBus extends CryoEventBus with SubchannelClassification {
  import EventSerialization._

  type Classifier = String
  type Subscriber = WebSocketFrameEvent

  protected def classify(event: Event) = event.path
  protected def subclassification = new Subclassification[Classifier] {
    def isEqual(x: Classifier, y: Classifier) = x == y
    def isSubclass(x: Classifier, y: Classifier) = x.startsWith(y)
  }

  // FIXME add synchronized + volatile
  protected def publish(event: Event, subscriber: Subscriber): Unit = {
    ignore.get(subscriber.channel.getId) match { // FIXME Ignore doesn't work !
      case Some(filters) if filters.exists(_.findFirstIn(event.path).isDefined) => // ignore
      case _ =>
        println("-->" + event)
        subscriber.write(event)
    }
  }

  private var ignore = Map[Int, Set[Regex]]()
  def addIgnoreSubscription(subscriber: Subscriber, subscription: String) = {
    ignore = ignore.updated(subscriber.channel.getId,
      ignore.getOrElse(subscriber.channel.getId, Set[Regex]()) + subscription.r)
  }

  def removeIgnoreSubscription(subscriber: Subscriber, subscription: String) = {
    ignore = ignore.updated(subscriber.channel.getId,
      ignore.getOrElse(subscriber.channel.getId, Set[Regex]()) - subscription.r)
  }
}

class CryoSocket extends Actor {
  import EventSerialization._

  def receive = {
    case wsFrame: WebSocketFrameEvent =>
      val m = wsFrame.readText
      println(s"receive: ${m}")
      val event = Serialization.read[RequestEvent](m)
      //val json = parse(wsFrame.readText)
      //val event = json.extract[RequestEvent]
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
        case RefreshInventory(maxAge) =>
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
*/
  def getDirectoryContent(directory: File, fileSelection: Iterable[String], fileFilters: scala.collection.Map[String, String]) = {
    //println("getDirectoryContent(%s, %s)".format(directory, fileSelection.mkString("(", ",", ")")))
    def removeTrailingSlash(s: String): String = {
      if (s.endsWith("/"))
        s.substring(0, s.length - 1)
      else 
        s
    }
    val dirContent = Option(directory.listFiles).getOrElse(Array[File]())
    for (f <- dirContent) {
      val filePath = removeTrailingSlash(Config.baseURI.relativize(f.toURI).getPath)
      
      To be continued
    }
    
    
    
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
