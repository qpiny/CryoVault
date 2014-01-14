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

case class BlockLocation(id: Long, hash: Hash, archiveId: UUID, offset: Long, size: Int)

class HashCatalog(_cryoctx: CryoContext) extends CryoActor(_cryoctx) with Stash {
  private val content = ArrayBuffer.empty[BlockLocation]
  private val hashIndex = HashMap.empty[Hash, BlockLocation]
  private var lastBlockId: Long = 0

  val catalogId = new UUID(0x0000000000001000L, 0xC47000000003L)
  val catalogGlacierId = "catalog"
  val reservedBlockLocation = BlockLocation(-1, Hash(Array.emptyByteArray), catalogId, 0, 0)

  override def preStart = {
    (cryoctx.datastore ? GetDataEntry(catalogId)) map {
      case DataEntry(_, _, _, _, _, size, _) => loadFromDataStore(size)
      case NotFound(_, _, _) => log.warn("Catalog data not found in datastore, using empty catalog")
    }
  }

  override def postStop = save

  def receive = cryoReceive {
    case PrepareToDie() => sender ! ReadyToDie()

    case GetCatalogContent(blockIds) =>
      blockIds match {
        case None => sender ! CatalogContent(content.toList)
        case Some(bids) => sender ! CatalogContent(content.filter(bl => bids.contains(bl.id)).toList)
      }

    case ReserveBlock(block) =>
      hashIndex.get(block.hash) match {
        case Some(bl) if bl != reservedBlockLocation =>
          sender ! bl
        case _ =>
          hashIndex += block.hash -> reservedBlockLocation
          sender ! Done()
        
      }
//    case GetBlockLocation(block) =>
//      hashIndex.get(block.hash) match {
//        case None =>
//          sender ! BlockLocationNotFound(block.hash)
//        case Some(bl) =>
//          if (bl == reservedBlockLocation) {
//            stash()
//          } else {
//            sender ! bl
//          }
//      }

    case AddBlock(block, archiveId, offset) =>
      val previousValue = hashIndex.get(block.hash)
      if (previousValue.isDefined && previousValue.get == reservedBlockLocation)
        unstashAll
      lastBlockId += 1
      val blockLocation = BlockLocation(lastBlockId, block.hash, archiveId, offset, block.size)
      content += blockLocation
      hashIndex += block.hash -> blockLocation
      sender ! BlockAdded(lastBlockId)
  }

  private def save() = {
    (cryoctx.datastore ? CreateData(Some(catalogId), DataType.Internal))
      .eflatMap("Fail to create catalog", {
        case Created(id) =>
          cryoctx.datastore ? WriteData(id, ByteString(Json.write(content)))
      }).eflatMap("Fail to write catalog", {
        case DataWritten(id, _, _) =>
          cryoctx.datastore ? PackData(id, catalogGlacierId)
      }).onComplete({
        case Success(DataPacked(_, _)) =>
          log.info("Catalog saved")
        case o: Any =>
          log(cryoError("Fail to close catalog", o))
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
          lastBlockId = if (content.isEmpty) 0 else content.maxBy(_.id).id
        case o: Any =>
          log(cryoError("Fail to read catalog data", o))
      })
  }
}