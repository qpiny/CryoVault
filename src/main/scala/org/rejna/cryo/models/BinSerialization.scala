package org.rejna.cryo.models

import java.nio.{ ByteBuffer, BufferOverflowException }
import java.nio.channels.FileChannel

import sbinary._
import sbinary.Operations._

import org.joda.time.DateTime


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
  implicit val DateTimeFormat = wrap[DateTime, Long](_.getMillis, new DateTime(_))
  implicit val HashFormat = wrap[Hash, Array[Byte]](_.value, new Hash(_))
  implicit val FileFilterFormat = wrap[FileFilter, String](_.toString, FileFilterParser.parse(_) match {
    case Right(ff) => ff
    case Left(message) => throw ParseError(message)
  })

//  implicit def BlockLocationFormat = asProduct4(
//    (hash: Hash, archiveId: String, offset: Long, size: Int) => BlockLocation(hash, Cryo.inventory.archives(archiveId), offset, size))(
//      (bl: BlockLocation) => (bl.hash, bl.archive.id, bl.offset, bl.size))
}