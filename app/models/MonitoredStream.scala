package models

import java.io.{ File, FileInputStream, InputStream, FilterInputStream, OutputStream, FilterOutputStream }


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

class MonitoredInputStream(val attributeBuilder: AttributeBuilder, val title: String, val file: File) extends InputStream with Transfer {

  var input = new FileInputStream(file)
  var mark = 0L
  transferredBytes = 0
  totalBytes = file.length

  override def reset = {
    input.close
    input = new FileInputStream(file)

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