package org.rejna.cryo.models

import scala.collection.JavaConversions._

import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption._
import java.nio.channels.FileChannel

import com.amazonaws.services.s3.internal.InputSubstream

case object InvalidStateException extends Exception

//case class BlockLocation(val hash: Hash, val archiveId: String, val offset: Long, val size: Int) {
//  def read = {
//    ByteBuffer.allocate(size)
//    val channel = FileChannel.open(arc.file, Set(CREATE, TRUNCATE_EXISTING, WRITE)) //, READ)
//    try {
//      val buffer = ByteBuffer.allocate(size)
//      channel.read(buffer, offset)
//      buffer
//    } finally {
//      channel.close()
//    }
//  }
//}
//
//case class Block(val data: Array[Byte]) {
//  lazy val hash = Hash(data)
//  lazy val size = data.size
//}
//
//object Block {
//  def apply(buffer: ByteBuffer) = {
//    val data = Array.ofDim[Byte](buffer.remaining)
//    buffer.get(data)
//    new Block(data)
//  }
//}
