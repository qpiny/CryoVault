package org.rejna.cryo.models

import scala.collection.JavaConversions._

import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption._
import java.nio.channels.FileChannel

import com.amazonaws.services.s3.internal.InputSubstream

object DataType extends Enumeration {
  type DataType = Value
  val Data, Index, Internal = Value
  
  def unapply(dt: String) = DataType.withName(dt)
}

object DataStatus extends Enumeration {
  type ObjectStatus = Value
  val Readable, Writable, Remote = Value
}

object SnapshotStatus extends Enumeration {
  type SnapshotStatus = Value
  val Creating, Created, Uploading, Remote, Downloading = Value
}

//object ObjectStatus {
//  case class Creating() extends ObjectStatus
//  case class Uploading() extends ObjectStatus
//  case class Cached(glacierId: String) extends ObjectStatus { override def getGlacierId = Some(glacierId) }
//  case class Remote(glacierId: String) extends ObjectStatus { override def getGlacierId = Some(glacierId) }
//  case class Downloading(glacierId: String) extends ObjectStatus { override def getGlacierId = Some(glacierId) }
//  case class Unknown() extends ObjectStatus
//  
//  def apply(name: String) = {
//    val regex = """(\w+)(?:\((\w*)\))?""".r
//    val regex(clazz, id) = name
//    clazz match {
//      case "Creating" => Creating()
//      case "Uploading" => Uploading()
//      case "Cached" if id != null => Cached(id)
//      case "Remote" if id != null => Remote(id)
//      case "Downloading" if id != null => Downloading(id)
//      case "Unknown" => Unknown()
//    }
//  }
//}



//case class BlockLocation(val hash: Hash, val archiveId: String, val offset: Long, val size: Int) {
//  def read = {
//    ByteBuffer.allocate(size)
//    val channel = FileChannel.open(arc.file, CREATE, TRUNCATE_EXISTING, WRITE) //, READ)
//    try {
//      val buffer = ByteBuffer.allocate(size)
//      channel.read(buffer, offset)
//      buffer
//    } finally {
//      channel.close()
//    }
//  }
//}

case class Block(val data: Array[Byte])(implicit cryoctx: CryoContext) {
  lazy val hash = Hash(data)
  lazy val size = data.size
}

object Block {
  def apply(buffer: ByteBuffer)(implicit cryoctx: CryoContext) = {
    val data = Array.ofDim[Byte](buffer.remaining)
    buffer.get(data)
    new Block(data)
  }
}
