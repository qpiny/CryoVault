package org.rejna.cryo.models

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels._
import akka.actor._
import com.typesafe.config.Config

case object MoreData
case object EndOfData

class SourceActor(channel: ReadableByteChannel, destination: ActorRef) extends Actor {
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