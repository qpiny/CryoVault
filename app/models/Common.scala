package models

import java.io.{ File, FileInputStream }

import com.amazonaws.services.s3.internal.InputSubstream

import CryoStatus._

case object InvalidStateException extends Exception

case class BlockLocation(val hash: Hash, val archive: Archive, val offset: Long, val size: Int) {
  def read = {
    val arc = archive match {
      case a: LocalArchive =>
        a.remoteArchive.getOrElse { throw InvalidStateException }
      case a: RemoteArchive =>
        if (a.state != Cached) throw InvalidStateException
        a
    }
    new InputSubstream(new FileInputStream(arc.file), offset, size, true)
  }
}

case class Block(val data: Array[Byte]) {
  lazy val hash = Hash(data)
  lazy val size = data.size
}
