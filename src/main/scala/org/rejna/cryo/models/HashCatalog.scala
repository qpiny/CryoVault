package org.rejna.cryo.models

import scala.language.postfixOps
import scala.collection.mutable.{ HashMap, ArrayBuffer }
import scala.concurrent.Future
import scala.util.{ Success, Failure }

import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption._
import java.nio.channels.FileChannel
import java.util.UUID

import akka.event.Logging.Error
import akka.actor.Stash
import akka.util.ByteString

sealed abstract class HashCatalogRequest extends Request
sealed abstract class HashCatalogResponse extends Response
sealed abstract class HashCatalogError(val message: String, val cause: Throwable = Error.NoCause) extends GenericError {
  val source = classOf[HashCatalog].getName
  val marker = Markers.errMsgMarker
}

case class GetBlockLocation(block: Block, reserveIfNotFound: Boolean) extends HashCatalogRequest
case class BlockLocation(id: Int, hash: Hash, archiveId: UUID, offset: Long, size: Int) extends HashCatalogResponse

case class AddBlock(block: Block, archiveId: UUID, offset: Long) extends HashCatalogRequest
case class BlockAdded(blockId: Int) extends HashCatalogResponse

case class BlockLocationNotFound(hash: Hash) extends HashCatalogError("Blocklocation was not found")

case class GetCatalogContent() extends HashCatalogRequest
case class CatalogContent(catalog: List[BlockLocation]) extends HashCatalogResponse

class HashCatalog(_cryoctx: CryoContext) extends CryoActor(_cryoctx) with Stash {
  private val content = ArrayBuffer.empty[BlockLocation]
  private val hashIndex = HashMap.empty[Hash, BlockLocation]

  val catalogId = new UUID(0x0000000000001000L, 0xC47000000003L)
  val catalogGlacierId = "catalog"
  val reservedBlockLocation = BlockLocation(-1, Hash(Array.emptyByteArray), catalogId, 0, 0)

  override def preStart = {
    (cryoctx.datastore ? GetDataStatus(catalogId)) map {
      case DataStatus(_, _, _, _, size, _) => loadFromDataStore(size)
      case DataNotFoundError(_, _, _) => log.warn("Catalog data not found in datastore, using empty catalog")
    }
  }

  override def postStop = save

  def receive = cryoReceive {
    case PrepareToDie() => sender ! ReadyToDie()

    case m: GetCatalogContent => sender ! CatalogContent(content.toList)

    case GetBlockLocation(block, reserveIfNotFound) =>
      hashIndex.get(block.hash) match {
        case None =>
          if (reserveIfNotFound) {
            hashIndex += block.hash -> reservedBlockLocation
            stash()
          } else {
            sender ! BlockLocationNotFound(block.hash)
          }
        case Some(bl) =>
          if (bl == reservedBlockLocation) {
            stash()
          } else {
            sender ! bl
          }
      }

    case AddBlock(block, archiveId, offset) =>
      val previousValue = hashIndex.get(block.hash)
      if (previousValue.isDefined && previousValue.get == reservedBlockLocation)
        unstashAll
      val blockLocation = BlockLocation( /* FIXME */ 0, block.hash, archiveId, offset, block.size)
      content += blockLocation
      hashIndex += block.hash -> blockLocation
  }

  private def save() = {
    (cryoctx.datastore ? CreateData(Some(catalogId), DataType.Internal))
      .eflatMap("Fail to create catalog", {
        case DataCreated(id) =>
          cryoctx.datastore ? WriteData(id, ByteString(Json.write(content)))
      }).eflatMap("Fail to write catalog", {
        case DataWritten(id, _, _) =>
          cryoctx.datastore ? CloseData(id)
      }).onComplete({
        case Success(DataClosed(id)) =>
          log.info("Catalog saved")
        case o: Any =>
          log(CryoError("Fail to close catalog", o))
      })
  }

  private def loadFromDataStore(size: Long) = {
    (cryoctx.datastore ? ReadData(catalogId, 0, size.toInt))
      .onComplete({
        case Success(DataRead(id, position, buffer)) =>
          val message = buffer.decodeString("UTF-8")
          val catalog = Json.read[Array[BlockLocation]](message)
          content ++= catalog
          hashIndex ++= catalog.map(bl => bl.hash -> bl)
        case o: Any =>
          log(CryoError("Fail to read catalog data", o))
      })
  }
}