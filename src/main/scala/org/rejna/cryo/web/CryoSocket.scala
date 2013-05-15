package org.rejna.cryo.web

import scala.util.matching.Regex
import scala.concurrent.duration._
import scala.collection.JavaConversions._
import scala.language.implicitConversions

import akka.actor.Actor

import java.nio.file.{ Path, Files, FileSystems, AccessDeniedException }

import org.rejna.cryo.models.{ Cryo, ArchiveType, LocalSnapshot, RemoteSnapshot, Config, CryoEventBus }
import akka.event.EventBus
import akka.event.SubchannelClassification
import akka.util.Subclassification
import net.liftweb.json._
import org.mashupbots.socko.events.WebSocketFrameEvent
import org.rejna.cryo.models._

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

object CryoSocketBus extends CryoEventBus with SubchannelClassification with LoggingClass {
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
    ignore.get(subscriber.channel.getId) match {
      case Some(filters) if filters.exists(_.findFirstIn(event.path).isDefined) => // ignore
      case _ =>
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

class CryoSocket extends Actor with LoggingClass {
  import EventSerialization._

  def receive = {
    case wsFrame: WebSocketFrameEvent =>
      val m = wsFrame.readText
      val event = Serialization.read[RequestEvent](m)
      event match {
        case Subscribe(subscription) =>
          CryoSocketBus.subscribe(wsFrame, subscription)
        case Unsubscribe(subscription) =>
          CryoSocketBus.unsubscribe(wsFrame, subscription)
        case AddIgnoreSubscription(subscription) =>
          CryoSocketBus.addIgnoreSubscription(wsFrame, subscription)
        case RemoveIgnoreSubscription(subscription) =>

        case CreateSnapshot() =>
          log.info("Creating new snapshot")
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
          val dir = Config.baseDirectory.resolve(directory)
          wsFrame.write(new SnapshotFiles(snapshotId, directory, getDirectoryContent(dir, files, snapshot.fileFilters).toList))
        }
        case UpdateSnapshotFileFilter(snapshotId, directory, filter) =>
          val snapshot = Cryo.inventory.snapshots(snapshotId)
          snapshot match {
            case ls: LocalSnapshot =>
              if (filter == "")
                ls.fileFilters -= directory
              else
                FileFilterParser.parse(filter).fold(
                  message => log.error("UpdateSnapshotFileFilter has failed : " + message),
                  filter => ls.fileFilters(directory) = filter)
            case _ => log.error("UpdateSnapshotFileFilter: File filters in remote snapshot are immutable")
          }
        case UploadSnapshot(snapshotId) =>
          val snapshot = Cryo.inventory.snapshots(snapshotId)
          snapshot match {
            case ls: LocalSnapshot => ls.create
            case _ => log.error("UploadSnapshot: Remote snapshot can't be updaloaded")
          }
        case msg => log.warn("Unknown message has been received : " + msg)
      }

  }

  def getDirectoryContent(directory: Path, fileSelection: Iterable[String], fileFilters: scala.collection.Map[String, FileFilter]): Iterable[FileElement] = {
    try {
      val dirContent = Files.newDirectoryStream(directory)

      for (f <- dirContent) yield {
        val filePath = Config.baseDirectory.relativize(f)
        val fileSize = for (
          fs <- fileSelection;
          fp = FileSystems.getDefault.getPath(fs);
          if fp.startsWith(filePath)
        ) yield Files.size(Config.baseDirectory.resolve(fp))

        new FileElement(f, fileSize.size, fileSize.sum, fileFilters.get(filePath.toString.replace(java.io.File.separatorChar, '/')))
      }
    } catch {
      case e: AccessDeniedException => Some(new FileElement(directory.resolve("_Access_denied_"), 0, 0, None))
    }
  }
}
