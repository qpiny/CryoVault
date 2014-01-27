package org.rejna.cryo.models

import java.security.MessageDigest

import java.nio.ByteBuffer
import java.nio.file.Path
import java.nio.file.StandardOpenOption._
import java.nio.channels.FileChannel

class Hash(val value: Array[Byte]) {
  override def toString = value.map("%02X" format _).mkString
  // TODO equals HashVersion
}

//class HashVersion(value: Array[Byte], val version: Int) extends Hash(value) {
//  lazy val hash = new Hash(value)
//  override def toString = s"${super.toString}-${version}"
//  // TODO equals Hash
//}
//
//object HashVersion {
//  def apply(value: Array[Byte], version: Int) = new HashVersion(value, version)
//}

object Hash {
  //  def apply(file: Path) = {
  //    val md = MessageDigest.getInstance(Config.hashAlgorithm)
  //
  //    val buffer = ByteBuffer.allocate(Config.bufferSize.intValue)
  //    val input = FileChannel.open(file, READ)
  //    try {
  //      Iterator.continually(input.read(buffer.clear.asInstanceOf[ByteBuffer]))
  //        .takeWhile(_ != -1)
  //        .filter(_ > 0)
  //        .foreach(size => md.update(buffer.array, 0, size))
  //    } finally {
  //      input.close
  //    }
  //    new Hash(md.digest)
  //  }
  //
  def digestLength(implicit cryoctx: CryoContext) =
    cryoctx.hashAlgorithm.getDigestLength

  def apply(data: Array[Byte])(implicit cryoctx: CryoContext) = {
    new Hash(cryoctx.hashAlgorithm.digest(data))
  }
  //
  //  def apply(hex: String) = {
  //    val hl = hex.toList
  //    val h = List.range(0, hl.size, 2).map { i => java.lang.Integer.parseInt(hl.slice(i, i + 2).mkString, 16).toByte }
  //    new Hash(h.toArray)
  //  }
  //
  //  def unapply(h: Hash): Option[Array[Byte]] = Some(h.value)
}