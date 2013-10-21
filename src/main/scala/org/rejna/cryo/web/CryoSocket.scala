package org.rejna.cryo.web

import scala.util.Success
import scala.util.matching.Regex
import scala.util.control.Exception._
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
import org.mashupbots.socko.events.WebSocketFrameEvent
import org.jboss.netty.channel.Channel
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame
import org.rejna.cryo.models._

class UnexceptionalPartial[-A, +B](exceptionCatcher: Catcher[B])(unsafe: PartialFunction[A, B]) extends PartialFunction[A, B] {
  def isDefinedAt(a: A) = unsafe.isDefinedAt(a)
  def apply(a: A) = catching(exceptionCatcher) { unsafe(a) }
}

case class Exit() extends Request

class CryoSocket(val cryoctx: CryoContext, channel: Channel) extends Actor with LoggingClass {
  implicit val timeout = Timeout(10 seconds)
  implicit val executionContext = context.system.dispatcher
  val ignore = ListBuffer[Regex]()

  override def postStop = {
    CryoWeb.unregisterWebSocket(channel)
    CryoEventBus.unsubscribe(self)
  }

  def send[T <: AnyRef](message: T)(implicit t : Manifest[T]) = {
    if (channel.isOpen) {
      channel.write(new TextWebSocketFrame(Json.write(message)))
//        message match {
//          case m: CryoMessage => Serialization.write(m)
//          case ml: Iterable[_] => Serialization.write(ml)
//        }))
    } else {
      println("WebSocket is closed, stopping actor")
      context.stop(self)
    }
  }
  def receive = {
   case wsFrame: WebSocketFrameEvent =>
      val m = wsFrame.readText
      log.debug("Receive from websocket: " + m)
      val event = Json.read[Request](m)
      log.info("Receive from websocket: " + event)
      event match {
        case Subscribe(subscription) =>
          CryoEventBus.subscribe(self, subscription)
        case Unsubscribe(subscription) =>
          CryoEventBus.unsubscribe(self, subscription)
        case AddIgnoreSubscription(subscription) =>
          ignore += subscription.r
        case RemoveIgnoreSubscription(subscription) =>
          ignore -= subscription.r
        case Exit() =>
          cryoctx.status = "Stopping"
          cryoctx.shutdown

        case sr: SnapshotRequest =>
          cryoctx.inventory ! sr
        case ie: InventoryRequest =>
          cryoctx.inventory ! ie
        case cr: CryoRequest =>
          cryoctx.cryo ! cr
        case mr: ManagerRequest =>
          cryoctx.manager ! mr
      }

    case event: Event if ignore.exists(_.findFirstIn(event.path).isDefined) => // ignore

    case msg: CryoMessage =>
      log.debug(s"Sending message to websocket : ${msg}")
      send(msg)
  }

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
