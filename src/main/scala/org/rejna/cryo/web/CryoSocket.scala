package org.rejna.cryo.web

import scala.util.matching.Regex
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.language.{ implicitConversions, postfixOps }

import akka.actor.{ Actor, OneForOneStrategy }
import akka.actor.SupervisorStrategy._
import akka.pattern.ask
import akka.util.Timeout

import java.nio.file.{ Path, Files, FileSystems, AccessDeniedException }
import java.nio.channels.ClosedChannelException

import org.rejna.cryo.models._
import akka.event.EventBus
import akka.event.SubchannelClassification
import akka.util.Subclassification
import net.liftweb.json._
import org.mashupbots.socko.events.WebSocketFrameEvent
import org.jboss.netty.channel.Channel
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame
import org.rejna.cryo.models._

object EventTypeHints extends TypeHints {
  val hints =
    //    classOf[UploadSnapshot] ::
    //      classOf[UpdateSnapshotFileFilter] ::
    classOf[Unsubscribe] ::
      classOf[Subscribe] ::
      classOf[RemoveIgnoreSubscription] ::
      classOf[RefreshInventory] ::
      classOf[GetSnapshotList] ::
      //      classOf[GetSnapshotFiles] ::
      //      classOf[GetArchiveList] ::
      classOf[CreateSnapshot] ::
      classOf[SnapshotCreated] ::
      classOf[SnapshotCreated] ::
      classOf[AddIgnoreSubscription] ::
      //      classOf[ArchiveList] ::
      classOf[SnapshotList] ::
      //      classOf[AddFile] ::
      //      //classOf[ArchiveCreation] ::
      //      classOf[SnapshotFiles] ::
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

  case class EventSender(channel: Channel) {
    def send(message: CryoMessage) = channel.write(new TextWebSocketFrame(Serialization.write(message)))
    def send(messageList: Iterable[CryoMessage]) = channel.write(new TextWebSocketFrame(Serialization.write(messageList)))
  }

  implicit def toEventSender(channel: Channel) = EventSender(channel)
}

class CryoSocket(cryoctx: CryoContext, channel: Channel) extends Actor with LoggingClass {
  import EventSerialization._
  implicit val timeout = Timeout(10 seconds)
  implicit val executionContext = context.system.dispatcher
  val ignore = ListBuffer[Regex]()

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
    case _: ClosedChannelException =>
      CryoWeb.unregisterWebSocket(channel)
      Stop
    case _: Exception => Resume
  }
  def receive = {
    case wsFrame: WebSocketFrameEvent =>
      val m = wsFrame.readText
      val event = Serialization.read[Request](m)
      event match {
        case Subscribe(subscription) =>
          CryoEventBus.subscribe(self, subscription)
        case Unsubscribe(subscription) =>
          CryoEventBus.unsubscribe(self, subscription)

        // TODO manager ignore subscription in this actor
        case AddIgnoreSubscription(subscription) =>
          ignore += subscription.r
        case RemoveIgnoreSubscription(subscription) =>
          ignore -= subscription.r

        case sr: SnapshotRequest =>
          cryoctx.inventory ! sr
        case ie: InventoryRequest =>
          cryoctx.inventory ! ie

      }
    case ArchiveIdList(archiveIds) =>
      Future.sequence(archiveIds.map {
        id =>
          (cryoctx.datastore ? GetDataStatus(id)).map {
            case ds: DataStatus => Some(ds)
            case _: Any => None
          }
      }).onSuccess {
        case msgList => channel.send(ArchiveList(msgList.flatten))
      }
    case SnapshotIdList(snapshots) =>
      Future.sequence(snapshots.map {
        case (id, _) => (cryoctx.datastore ? GetDataStatus(id)).map {
          case ds: DataStatus => Some(ds)
          case _: Any => None
        }
      }).onSuccess {
        case msgList => channel.send(SnapshotList(msgList.flatten.toList))
      }
    case event: Event if ignore.exists(_.findFirstIn(event.path).isDefined) => // ignore
    case msg: CryoMessage =>
      channel.send(msg)

    //        case GetArchiveList() =>
    //          wsFrame.write(ArchiveList(Cryo.inventory.archives.values.toList))
    //        case GetSnapshotList() =>
    //          wsFrame.write(SnapshotList(Cryo.inventory.snapshots.values.toList))
    //        case RefreshInventory(maxAge) =>
    //          Cryo.inventory.update(maxAge)
    //        case GetSnapshotFiles(snapshotId, directory) => {
    //          val snapshot = Cryo.inventory.snapshots(snapshotId)
    //          val files = snapshot match {
    //            case ls: LocalSnapshot => ls.files()
    //            case rs: RemoteSnapshot => rs.remoteFiles.map(_.file.toString)
    //          }
    //          val dir = Config.baseDirectory.resolve(directory)
    //          wsFrame.write(new SnapshotFiles(snapshotId, directory, getDirectoryContent(dir, files, snapshot.fileFilters).toList))
    //        }
    //        case UpdateSnapshotFileFilter(snapshotId, directory, filter) =>
    //          val snapshot = Cryo.inventory.snapshots(snapshotId)
    //          snapshot match {
    //            case ls: LocalSnapshot =>
    //              if (filter == "")
    //                ls.fileFilters -= directory
    //              else
    //                FileFilterParser.parse(filter).fold(
    //                  message => log.error("UpdateSnapshotFileFilter has failed : " + message),
    //                  filter => ls.fileFilters(directory) = filter)
    //            case _ => log.error("UpdateSnapshotFileFilter: File filters in remote snapshot are immutable")
    //          }
    //        case UploadSnapshot(snapshotId) =>
    //          val snapshot = Cryo.inventory.snapshots(snapshotId)
    //          snapshot match {
    //            case ls: LocalSnapshot => ls.create
    //            case _ => log.error("UploadSnapshot: Remote snapshot can't be updaloaded")
    //          }
    //        case msg => log.warn("Unknown message has been received : " + msg)

  }

  //  def getDirectoryContent(directory: Path, fileSelection: Iterable[String], fileFilters: scala.collection.Map[String, FileFilter]): Iterable[FileElement] = {
  //    try {
  //      val dirContent = Files.newDirectoryStream(directory)
  //
  //      for (f <- dirContent) yield {
  //        val filePath = Config.baseDirectory.relativize(f)
  //        val fileSize = for (
  //          fs <- fileSelection;
  //          fp = FileSystems.getDefault.getPath(fs);
  //          if fp.startsWith(filePath)
  //        ) yield Files.size(Config.baseDirectory.resolve(fp))
  //
  //        new FileElement(f, fileSize.size, fileSize.sum, fileFilters.get(filePath.toString.replace(java.io.File.separatorChar, '/')))
  //      }
  //    } catch {
  //      case e: AccessDeniedException => Some(new FileElement(directory.resolve("_Access_denied_"), 0, 0, None))
  //    }
  //  }
}
