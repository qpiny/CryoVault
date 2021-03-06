package org.rejna.cryo.models

import scala.language.implicitConversions

import akka.util.{ ByteString, ByteStringBuilder }

import java.nio.{ ByteBuffer, BufferOverflowException, ByteOrder }
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.util.{ UUID, Date }

import sbinary._
import sbinary.Operations._

object ByteStringSerializer {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  case class BSSerializer(bsBuilder: ByteStringBuilder) {
    def putString(s: String): ByteStringBuilder = {
      val bs = ByteString(s, "UTF-8")
      bsBuilder.putInt(bs.length)
      bsBuilder ++= bs
    }

    def putPath(p: Path) = putString(p.toString)

    def putBlockLocation(bl: BlockLocation): ByteStringBuilder = {
      bsBuilder.putLong(bl.id)
      putHash(bl.hash)
      putString(bl.archiveId.toString)
      bsBuilder.putLong(bl.offset)
      bsBuilder.putInt(bl.size)
      bsBuilder
    }
    //    def putHashVersion(hash: HashVersion): ByteStringBuilder = {
    //      bsBuilder.putBytes(hash.value)
    //      bsBuilder.putInt(hash.version)
    //    }
    def putHash(hash: Hash): ByteStringBuilder = {
      bsBuilder.putBytes(hash.value)
    }
    def putBoolean(b: Boolean): ByteStringBuilder = {
      if (b) bsBuilder.putByte(1)
      else bsBuilder.putByte(0)
    }
  }

  implicit def bs2bss(bsBuilder: ByteStringBuilder) = BSSerializer(bsBuilder)

  case class BSDeserializer(buffer: ByteBuffer, cryoctx: CryoContext) {

    def getString = {
      val str = Array.ofDim[Byte](buffer.getInt)
      buffer.get(str)
      new String(str, "UTF-8")
    }

    def getFilter = {
      val path = getPath
      val filter = FileFilterParser.parse(getString)
      (path, filter)
    }

    def getPath = {
      cryoctx.filesystem.getPath(getString)
    }

    def getFile = {
      val path = getPath
      val blocks = Iterator.continually { buffer.getLong }
        .takeWhile(_ != -1)
        .toList
      (path, blocks)
    }

    def getHash = {
      val hash = Array.ofDim[Byte](cryoctx.hashAlgorithm.getDigestLength)
      buffer.get(hash)
      new Hash(hash)
    }

    def getBlockLocation = {
      val id = buffer.getLong
      val hash = getHash
      val archiveId = UUID.fromString(getString)
      val offset = buffer.getLong
      val size = buffer.getInt
      BlockLocation(id, hash, archiveId, offset, size)
    }
  }

  implicit def bs2bsd(buffer: ByteBuffer)(implicit cryoctx: CryoContext) = BSDeserializer(buffer, cryoctx)
}

class ByteBufferOutput(fc: FileChannel) extends Output {
  val buffer = ByteBuffer.allocateDirect(1024) // FIXME config.bufferSize)
  def writeByte(value: Byte) = try {
    buffer.put(value)
  } catch {
    case e: BufferOverflowException =>
      buffer.flip
      fc.write(buffer)
      buffer.compact
      buffer.put(value)
  }

  override def writeAll(source: Array[Byte], offset: Int, length: Int) = try {
    buffer.put(source, offset, length)
  } catch {
    case e: BufferOverflowException =>
      buffer.flip
      fc.write(buffer)
      buffer.compact
      try { buffer.put(source, offset, length) }
      catch {
        case e: BufferOverflowException => super.writeAll(source, offset, length)
      }
  }

  def close = {
    while (buffer.hasRemaining)
      fc.write(buffer)
  }
}

object CryoBinary extends DefaultProtocol {
  import DefaultProtocol._

  implicit def IteratorFormat[A](implicit af: Format[A]) = new Format[Iterator[A]] {

    def reads(in: Input) = new Iterator[A] {
      private var _hasNext = in.readByte == 0
      def hasNext = _hasNext
      def next = {
        _hasNext = in.readByte == 0
        af.reads(in)
      }
    }

    def writes(out: Output, value: Iterator[A]) = {
      value.foreach { a =>
        out.writeByte(0)
        af.writes(out, a)
      }
      out.writeByte(1)
    }
  }
  implicit val DateTimeFormat = wrap[Date, Long](_.getTime, new Date(_))
  implicit val HashFormat = wrap[Hash, Array[Byte]](_.value, new Hash(_))
  implicit val FileFilterFormat = wrap[FileFilter, String](_.toString, FileFilterParser.parse(_) match {
    case Right(ff) => ff
    case Left(message) => throw ParseError(message)
  })

  //  implicit def BlockLocationFormat = asProduct4(
  //    (hash: Hash, archiveId: String, offset: Long, size: Int) => BlockLocation(hash, Cryo.inventory.archives(archiveId), offset, size))(
  //      (bl: BlockLocation) => (bl.hash, bl.archive.id, bl.offset, bl.size))
}