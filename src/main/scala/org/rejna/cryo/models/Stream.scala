package org.rejna.cryo.models

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }
import scala.annotation.tailrec

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels._

import akka.actor._
import akka.util.{ ByteIterator, Timeout }
import akka.pattern.ask

import com.typesafe.config.Config

case object MoreData
case object EndOfData

class StreamSourceActor(channel: ReadableByteChannel, destination: ActorRef) extends Actor {
  def receive: Receive = {
    case MoreData =>
      val buffer = ByteBuffer.allocate(1024) // FIXME
      if (channel.read(buffer) == -1) { // FIXME catch exception and send error
        destination ! EndOfData
        context.stop(self)
      } else {
        destination ! buffer
        self ! MoreData
      }
  }
}

class StreamDestinationActor(id: String, datastore: ActorRef)(implicit executionContext: ExecutionContext, timeout: Timeout) extends InputStream {
  var buffer: ByteIterator = ByteIterator.ByteArrayIterator.empty
  var position = 0
  var limit = 0
  var size = Await.result(
    (datastore ? GetDataStatus(id))
      .mapTo[DataStatus]
      .map { dataStatus =>
        if (dataStatus.status == EntryStatus.Created)
          dataStatus.size
        else 0
      }, 10 seconds)

  private def requestMoreData: Int = {
    if (limit < size)
      Await.result((datastore ? ReadData(id, limit, Math.min(1024, size - limit)))
        .map {
          case DataRead(id, position, data) =>
            // TODO check position == limit && id == id ?
            buffer ++= data
            val s = data.size
            limit += s
            s
          case _ => -1
        }, 10 seconds)
    else
      -1
  }

  @tailrec
  override def read: Int = {
    if (buffer.hasNext) {
      position += 1
      buffer.next().toInt & 0xff
    } else {
      if (available > 0 && requestMoreData >= 0)
        read
      else
        -1
    }
  }

  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    val n = math.min(available, len)
    if (available < len)
      -1
    else if (limit - position < n && requestMoreData >= 0)
      read(b, off, n)
    else {
      buffer.copyToArray(b, off, n)
      n
    }
  }
  
      override def skip(n: Long): Long = {
        val n = math.min(available, n)
  //      iterator.drop(nSkip)
  //      nSkip
  //    }
  //    override def available = streamSize.toInt - position
  //    override def close = {}
  //    override def read = {
  //      val b = buffer.getByte
  //      position += 1
  //      b
  //    }
  //    override def read(data: Array[Byte]) = 0
  //    override def read(data: Array[Byte], offset: Int, length: Int) = 0
  //  }

  //  def receive = {
  //    case DataRead(id, position, data) =>
  //      buffer ++= data
  //  }
}