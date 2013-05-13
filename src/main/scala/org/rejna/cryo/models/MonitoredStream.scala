package org.rejna.cryo.models

import java.io.{ InputStream, OutputStream, FilterOutputStream }
import java.nio.file.{ Path, Files }

object StreamOps {
  def copyStream(from: InputStream, to: OutputStream) = {
    val buffer = Array.ofDim[Byte](Config.bufferSize)
    Iterator continually (from read buffer) takeWhile (_ != -1) filter (_ > 0) foreach { to.write(buffer, 0, _) }
  }
}

class MonitoredOutputStream(val attributeBuilder: AttributeBuilder, val title: String, out: OutputStream, val size: Long) extends FilterOutputStream(out) with Transfer {
  totalBytes = size
  override def write(b: Array[Byte]) = {
    println("MonitoredOutputStream.write(%d)".format(b.length))
    out.write(b)
    transferredBytes = transferredBytes + b.length
  }
  override def write(b: Array[Byte], off: Int, len: Int) = {
    out.write(b, off, len)
    transferredBytes = transferredBytes + len
  }
  override def write(b: Int) = {
    out.write(b)
    transferredBytes = transferredBytes + 1
  }
}

class MonitoredInputStream(val attributeBuilder: AttributeBuilder, val title: String, val file: Path) extends InputStream with Transfer {

  var input = Files.newInputStream(file)
  var mark = 0L
  transferredBytes = 0
  totalBytes = Files.size(file)

  override def reset = {
    input.close
    input = Files.newInputStream(file)

    skipFully(mark)
    transferredBytes = mark
  }

  def skipFully(n: Long): Long = {
    if (n > 0) skipFully(n - input.skip(n))
    else 0
  }

  override def markSupported = true

  override def mark(limit: Int) = {
    mark = transferredBytes
  }

  override def available = input.available

  override def close = input.close

  override def read = {
    val r = input.read
    if (r != -1)
      transferredBytes += 1
    r
  }

  override def skip(n: Long) = {
    val r = input.skip(n)
    transferredBytes += r
    r
  }

  override def read(data: Array[Byte], off: Int, len: Int) = {
    val r = input.read(data, off, len)
    transferredBytes += r
    r
  }
}