package org.rejna.cryo.models

import scala.collection.JavaConversions._

import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption._
import java.nio.channels.FileChannel

import com.amazonaws.services.s3.internal.InputSubstream

import CryoStatus._

case object InvalidStateException extends Exception

case class BlockLocation(val hash: Hash, val archive: Archive, val offset: Long, val size: Int) {
  def read = {
    val arc = archive match {
      case la: LocalArchive =>
        la.remoteArchive.getOrElse { throw InvalidStateException }
      case ra: RemoteArchive =>
        if (ra.state != Cached) throw InvalidStateException
        ra
    }

    val channel = FileChannel.open(arc.file, Set(CREATE, TRUNCATE_EXISTING, WRITE)) //, READ)
    try {
      val buffer = ByteBuffer.allocate(size)
      channel.read(buffer, offset)
      buffer
    } finally {
      channel.close()
    }
  }
}

case class Block(val data: Array[Byte]) {
  lazy val hash = Hash(data)
  lazy val size = data.size
}
