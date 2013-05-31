package org.rejna.cryo.models

import scala.language.postfixOps
import scala.collection.mutable.HashMap
import scala.concurrent.Future
import scala.util.{ Success, Failure }

import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption._
import java.nio.channels.FileChannel

sealed abstract class HashCatalogRequest extends Request
sealed abstract class HashCatalogResponse extends Response
sealed abstract class HashCatalogError(message: String, cause: Throwable = null) extends CryoError(message, cause)

case class GetHashBlockLocation(hashVersion: HashVersion) extends HashCatalogRequest
case class GetBlockLocation(block: Block) extends HashCatalogRequest
case class BlockLocation(hashVersion: HashVersion, archiveId: String, offset: Long, size: Int) extends HashCatalogResponse

case class AddBlockLocation(blockLocations: BlockLocation*) extends HashCatalogRequest
case class BlockLocationAdded(added: List[BlockLocation], failed: List[BlockLocation])

case class BlockLocationNotFound(hashVersion: HashVersion) extends HashCatalogError("Blocklocation was not found")
case class HashCollision(message: String) extends HashCatalogError("Blocklocation was not found")

case class GetCatalogContent() extends HashCatalogRequest
case class CatalogContent(catalog: Map[HashVersion, BlockLocation]) extends HashCatalogResponse

class HashCatalog(val cryoctx: CryoContext) extends CryoActor {
  private val content = HashMap.empty[HashVersion, BlockLocation]
  private val hashVersions = HashMap.empty[Hash, List[HashVersion]]

  def cryoReceive = {
    case m: GetCatalogContent => sender ! CatalogContent(content.toMap)
    case GetHashBlockLocation(hash) =>
      content.get(hash) match {
        case None => sender ! BlockLocationNotFound(hash)
        case Some(bl) => sender ! bl
      }

    case GetBlockLocation(block) =>
      val requester = sender
      hashVersions.get(block.hash) match {
        case None => sender ! BlockLocationNotFound(HashVersion(block.hash.value, 1))
        case Some(hvs) =>
          hvs.foldLeft(Future(Right(List[BlockLocation]())): Future[Either[BlockLocation, List[BlockLocation]]]) {
            case (f, hv) => f flatMap {
              case Left(bl) => Future(Left(bl))
              case Right(lbs) =>
                content.get(hv) match {
                  case Some(bl) =>
                    sameContent(block, bl) map {
                      case None => Right(bl :: lbs)
                      case Some(true) => Left(bl)
                      case Some(false) => Right(lbs)
                    }
                  case None => Future(Right(lbs))
                }
            }
          } onComplete {
            case Success(Left(bl)) => requester ! bl
            case Success(Right(bls)) =>
              if (bls.size == 1) requester ! bls.head // we are optimistic
              else {
                val maxVersion = hashVersions(block.hash).map(_.version).max
                requester ! BlockLocationNotFound(HashVersion(block.hash.value, maxVersion + 1))
              }
            case Failure(e: CryoError) => requester ! e
            case Failure(e) => requester ! new CryoError("", e)
          }
      }
    case AddBlockLocation(blockLocations @ _*) =>
      var added = List[BlockLocation]()
      var failed = List[BlockLocation]()

      blockLocations foreach { blocation =>
        hashVersions += blocation.hashVersion.hash ->
          (blocation.hashVersion :: hashVersions.getOrElse(blocation.hashVersion.hash, List[HashVersion]()))
        content.get(blocation.hashVersion) match {
          case None =>
            content += blocation.hashVersion -> blocation
            added = blocation :: added

          case Some(bl) =>
            if (blocation == bl)
              added = blocation :: added
            else {
              failed = blocation :: failed // TODO addLogs
            }
        }
      }
      sender ! BlockLocationAdded(added, failed)
  }

  private def sameContent(block: Block, bl: BlockLocation): Future[Option[Boolean]] = {
    cryoctx.datastore ? ReadData(bl.archiveId, bl.offset, bl.size) map {
      case DataRead(_, _, buffer) => Some(block.data.deep == buffer.toArray.deep)
      case _: Any => None
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