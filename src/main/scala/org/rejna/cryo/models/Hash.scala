package org.rejna.cryo.models

import java.security.MessageDigest
import java.io.{ File, FileInputStream }

class Hash(val value: Array[Byte]) {
  override def toString = value.map("%02X" format _).mkString
  
  override def equals(a: Any) = a match {
    case Hash(v) => v.sameElements(value)
    case _ => false
  }
}

object Hash {
  def apply(file: File) = {
    val md = MessageDigest.getInstance(Config.hashAlgorithm)
    val buffer = Array.ofDim[Byte](Config.bufferSize)
    val fis = new FileInputStream(file)
    var nread = 0
    do {
      nread = fis.read(buffer)
      md.update(buffer, 0, nread)
    } while (nread == Config.bufferSize)
    fis.close
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