package org.rejna.cryo.models

import scala.language.postfixOps
import scala.collection.mutable.{ HashMap, ArrayBuffer }
import scala.concurrent.Future
import scala.util.{ Success, Failure }

import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption._
import java.nio.channels.FileChannel

import akka.event.Logging.Error

sealed abstract class HashCatalogRequest extends Request
sealed abstract class HashCatalogResponse extends Response
sealed abstract class HashCatalogError(val message: String, val cause: Throwable = Error.NoCause) extends GenericError {
  val source = classOf[HashCatalog].getName
  val marker = Markers.errMsgMarker
}

case class GetHashBlockLocation2(hash: Hash) extends HashCatalogRequest
case class GetBlockLocation(block: Block) extends HashCatalogRequest
case class BlockLocation(id: Int, hash: Hash, archiveId: String, offset: Long, size: Int) extends HashCatalogResponse

case class UpdateArchiveId(oldArchiveId: String, newArchiveId: String)
case class ArchiveIdUpdated(oldArchiveId: String, newArchiveId: String)

case class AddBlockLocation(blockLocation: BlockLocation) extends HashCatalogRequest
case class BlockLocationAdded(added: List[BlockLocation], failed: List[BlockLocation])

case class BlockLocationNotFound(hash: Hash) extends HashCatalogError("Blocklocation was not found")
//case class HashCollision(_message: String) extends HashCatalogError(_message)

case class GetCatalogContent() extends HashCatalogRequest
case class CatalogContent(catalog: List[BlockLocation]) extends HashCatalogResponse

class HashCatalog(_cryoctx: CryoContext) extends CryoActor(_cryoctx) {
  private val content = ArrayBuffer.empty[BlockLocation]
  private val hashIndex = HashMap.empty[Hash, BlockLocation]

  def receive = cryoReceive {
    case PrepareToDie() => sender ! ReadyToDie()
    case m: GetCatalogContent => sender ! CatalogContent(content.toList)
//    case GetHashBlockLocation(hash) =>
//      content.get(hash) match {
//        case None => sender ! BlockLocationNotFound(hash)
//        case Some(bl) => sender ! bl
//      }

    case GetBlockLocation(block) =>
      val _sender = sender
      hashIndex.get(block.hash) match {
        case None => sender ! BlockLocationNotFound(block.hash)
        case Some(bl) => sender ! bl
      }
      
    case UpdateArchiveId(oldArchiveId, newArchiveId) =>
      content.transform(_ match {
        case bl if bl.archiveId == oldArchiveId => bl.copy(archiveId = newArchiveId)
        case bl => bl
      })
      
//    case AddBlockLocation(blockLocations @ _*) =>
//      var added = List[BlockLocation]()
//      var failed = List[BlockLocation]()
//
//      blockLocations foreach { blocation =>
//        hashVersions += blocation.hashVersion.hash ->
//          (blocation.hashVersion :: hashVersions.getOrElse(blocation.hashVersion.hash, List[HashVersion]()))
//        content.get(blocation.hashVersion) match {
//          case None =>
//            content += blocation.hashVersion -> blocation
//            added = blocation :: added
//
//          case Some(bl) =>
//            if (blocation == bl)
//              added = blocation :: added
//            else {
//              failed = blocation :: failed // TODO addLogs
//            }
//        }
//      }
//      sender ! BlockLocationAdded(added, failed)
  }

  private def sameContent(block: Block, bl: BlockLocation): Future[Option[Boolean]] = {
    (cryoctx.datastore ? ReadData(bl.archiveId, bl.offset, bl.size)) map {
      case DataRead(_, _, buffer) => Some(block.data.deep == buffer.toArray.deep)
      case _: Any => None
    } recover {
      case t =>
        log.error("Fail to read data", t)
        None
    }
  }
  //    None
  //        val c = bl.archive match {
  //          case la: LocalArchive => Some(la.file)
  //          case ra: RemoteArchive => if (ra.state == Cached) Some(ra.file) else None
  //          case _: Any => None
  //        }
  //    
  //        c map { file =>
  //          val buffer = ByteBuffer.allocate(bl.size)
  //          val input = FileChannel.open(file, READ)
  //          try {
  //            input.read(buffer, bl.offset)
  //          } finally { input.close }
  //    
  //          buffer.flip
  //          buffer.compareTo(ByteBuffer.wrap(block.data)) == 0
  //        }
  //  }
  //
  //  def getOrUpdate(block: Block, createBlockLocation: => BlockLocation): BlockLocation = {
  //    content.get(block.hash) match {
  //      case None => // first block with this hash
  //        add(block, createBlockLocation)
  //      case Some(vbl) => // there is already a block with the same hash in catalog
  //        block.hash.version match {
  //          case Some(v) if vbl.get(v).isDefined => // block with same hash+version found in catalog
  //            val bl = vbl(v)
  //            checkCollision(block, bl) match {
  //              case Some(true) => // collision
  //                sys.error("Block hash collision (with same version); it should never happens")
  //              //case None => // don't know
  //              //case Some(false) => // no collision (blocks have the same content)
  //              case _: Any =>
  //                bl
  //            }
  //          case Some(v) => // vbl.get(v).isEmpty // hash version is not present in catalog
  //            add(block, createBlockLocation)
  //          case None => // hash hasn't a version yet
  //            vbl.find {
  //              case (v, bl) => checkCollision(block, bl).getOrElse(false)
  //            } match {
  //              case Some((v, bl)) =>
  //                block.hash.version = v
  //                bl
  //              case None =>
  //                if (vbl.size == 1) {
  //                  val (v, bl) = vbl.head
  //                  block.hash.version = v
  //                  bl
  //                } else
  //                  sys.error("Collision detected but it can't be solved")
  //            }
  //        }
  //    }
  //  }
  //}
}