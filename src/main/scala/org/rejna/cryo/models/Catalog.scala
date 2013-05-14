package org.rejna.cryo.models

import scala.language.postfixOps
import scala.collection.mutable.HashMap

import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption._
import java.nio.channels.FileChannel

import CryoStatus._

object Catalog {
  private val content = HashMap.empty[Hash, HashMap[Int, BlockLocation]]

  def get(hash: Hash): Option[BlockLocation] = {
    content.get(hash) flatMap { vbl =>
      hash.version match {
        case Some(v) =>
          vbl.get(v)
        case None =>
          if (vbl.size == 1) {
            val (v, bl) = vbl.head
            hash.version = v
            Some(bl)
          } else {
            sys.error("Hash collision and get block location without version")
          }
      }
    }
  }

  def ++=(entries: Map[Hash, BlockLocation]): Unit = {
    entries foreach {
      case (hash, location) =>
        val version = hash.version.getOrElse(sys.error("Update catalog with unversioned hash"))
        content.get(hash) match {
          case None =>
            content += hash -> HashMap(version -> location)
          case Some(vbl) =>
            vbl.get(version) match {
              case None =>
                vbl += version -> location
              case Some(bl) if bl != location =>
                sys.error("Hash version collision while updating catalog")
            }
        }
    }
  }

  def +=(block: Block, location: BlockLocation): BlockLocation = {
    content.get(block.hash) match {
      case None =>
        block.hash.version = 0
        content += block.hash -> HashMap(block.hash.version.get -> location)
      case Some(vbl) =>
        val version = (vbl.keySet.max) + 1
        block.hash.version = version
        vbl += version -> location
    }
    location
  }
  def add = += _

  private def checkCollision(block: Block, bl: BlockLocation): Option[Boolean] = {
    val c = bl.archive match {
      case la: LocalArchive => Some(la.file)
      case ra: RemoteArchive => if (ra.state == Cached) Some(ra.file) else None
      case _: Any => None
    }

    c map { file =>
      val buffer = ByteBuffer.allocate(bl.size)
      val input = FileChannel.open(file, READ)
      try {
        input.read(buffer, bl.offset)
      } finally { input.close }

      buffer.compareTo(ByteBuffer.wrap(block.data)) == 0
    }
  }

  def getOrUpdate(block: Block, createBlockLocation: => BlockLocation): BlockLocation = {
    content.get(block.hash) match {
      case None => // first block with this hash
        add(block, createBlockLocation)
      case Some(vbl) => // there is already a block with the same hash in catalog
        block.hash.version match {
          case Some(v) if vbl.get(v).isDefined => // block with same hash+version found in catalog
            val bl = vbl(v)
            checkCollision(block, bl) match {
              case Some(true) => // collision
                sys.error("Block hash collision (with same version); it should never happens")
              //case None => // don't know
              //case Some(false) => // no collision (blocks have the same content)
              case _: Any =>
                bl
            }
          case Some(v) => // vbl.get(v).isEmpty // hash version is not present in catalog
            add(block, createBlockLocation)
          case None => // hash hasn't a version yet
            vbl.find {
              case (v, bl) => checkCollision(block, bl).getOrElse(false)
            } match {
              case Some((v, bl)) =>
                block.hash.version = v
                bl
              case None =>
                if (vbl.size == 1) {
                  val (v, bl) = vbl.head
                  block.hash.version = v
                  bl
                } else
                  sys.error("Collision detected but it can't be solved")
            }
        }
    }
  }
}