package org.rejna.cryo.models

import scala.language.postfixOps
import scala.collection.mutable.HashMap

import akka.actor.Actor

import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption._
import java.nio.channels.FileChannel

sealed abstract class CatalogRequest extends Request
sealed abstract class CatalogResponse extends Response
sealed abstract class CatalogError(message: String, cause: Throwable = null) extends CryoError(message, cause)

case class GetBlockLocation(hash: Hash) extends CatalogRequest
case class BlockLocation(hash: Hash, archiveId: String, offset: Long, size: Int) extends CatalogResponse
case class AddBlockLocation(blockLocations: BlockLocation*) extends CatalogRequest
case class BlockLocationAdded(added: List[BlockLocation], failed: List[BlockLocation])
case class BlockLocationNotFound(hash: Hash) extends CatalogError("Blocklocation was not found")
case class HashCollision(message: String) extends CatalogError("Blocklocation was not found")

class Catalog(cryoctx: CryoContext) extends Actor {
  private val content = HashMap.empty[Hash, HashMap[Int, BlockLocation]]

  def receive = {
    case GetBlockLocation(hash) =>
      content.get(hash) match {
        case None => sender ! BlockLocationNotFound(hash)
        case Some(vbl) =>
          hash.version match {
            case Some(v) =>
              vbl.get(v) match {
                case None => sender ! BlockLocationNotFound(hash)
                case Some(bl) => sender ! bl
              }
            case None =>
              if (vbl.size == 1) {
                val (v, bl) = vbl.head
                sender ! bl
              } else {
                sender ! HashCollision("Hash collision and get block location without version")
              }
          }
      }

    case AddBlockLocation(blockLocations @ _*) =>
      var added = List[BlockLocation]()
      var failed = List[BlockLocation]()
      
      blockLocations foreach { bl =>
        content.get(bl.hash) match {
          case None => // need to check version
            content += bl.hash -> HashMap(bl.hash.version.get -> bl)
            added = bl :: added

          case Some(vbl) =>
            vbl.get(bl.hash.version.get) match {
              case None =>
                vbl += bl.hash.version.get -> bl
                added = bl :: added
              case Some(l) =>
                if (l == bl)
                  added = bl :: added
                else
                  failed = bl :: failed
            }
        }
      }
    sender ! BlockLocationAdded(added, failed)
  }
}

//    private def checkCollision(block: Block, bl: BlockLocation): Option[Boolean] = {
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