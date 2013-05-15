package org.rejna.cryo.models

import java.security.MessageDigest

import java.nio.ByteBuffer
import java.nio.file.Path
import java.nio.file.StandardOpenOption._
import java.nio.channels.FileChannel

class Hash(val value: Array[Byte], vers: Option[Int] = None) {
  private var _version = vers

  def version = _version

  def version_=(v: Int) = _version match { // version can be set only once
    case None => _version = Some(v)
    case _: Any => // ignore XXX add log ?
  }
  override def toString = value.map("%02X" format _).mkString

  override def equals(a: Any) = a match {
    case Hash(v) => v.sameElements(value)
    case _ => false
  }
}

object Hash {
  def apply(file: Path) = {
    val md = MessageDigest.getInstance(Config.hashAlgorithm)

    val buffer = ByteBuffer.allocate(Config.bufferSize.intValue)
    val input = FileChannel.open(file, READ)
    try {
      Iterator.continually(input.read(buffer.clear.asInstanceOf[ByteBuffer]))
        .takeWhile(_ != -1)
        .filter(_ > 0)
        .foreach(size => md.update(buffer.array, 0, size))
    } finally {
      input.close
    }
    new Hash(md.digest)
  }

  def apply(data: Array[Byte]) = {
    new Hash(MessageDigest.getInstance(Config.hashAlgorithm).digest(data))
  }

  def apply(hex: String) = {
    val hl = hex.toList
    val h = List.range(0, hl.size, 2).map { i => java.lang.Integer.parseInt(hl.slice(i, i + 2).mkString, 16).toByte }
    new Hash(h.toArray)
  }

  def unapply(h: Hash): Option[Array[Byte]] = Some(h.value)
}