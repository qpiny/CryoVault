package org.rejna.cryo.models

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }
import scala.annotation.tailrec
import scala.language.postfixOps

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

