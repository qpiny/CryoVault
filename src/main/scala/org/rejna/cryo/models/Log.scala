package org.rejna.cryo.models

import akka.actor.{ Actor, ActorContext }

import org.slf4j.{ Marker, LoggerFactory }
import org.slf4j.helpers.MessageFormatter
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.classic.{ Level, Logger, LoggerContext }
import ch.qos.logback.core.spi.FilterReply

case class Log(level: Level, marker: String, message: String) extends Event {
  val path = Log.levelToPath(level) + '#' + marker
}
object Log {
  val lc = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
  lc.addTurboFilter(LogDispatcher)

  def getLogger(clazz: Class[_]) = LoggerFactory.getLogger(clazz)

  val levelToPath = Map(
    Level.ALL -> "/log",
    Level.TRACE -> "/log/trace",
    Level.DEBUG -> "/log/trace/debug",
    Level.INFO -> "/log/trace/debug/info",
    Level.WARN -> "/log/trace/debug/info/warn",
    Level.ERROR -> "/log/trace/debug/info/warn/error")
}

trait LoggingClass {
  lazy implicit val log = Log.getLogger(this.getClass)
}

class CryoReceive(r: Actor.Receive)(implicit context: ActorContext, log: Logger) extends Actor.Receive {
  def isDefinedAt(o: Any): Boolean = {
    val handled = r.isDefinedAt(o)
    if (handled)
      log.info(s"Receive handled message ${o}")
    else
      log.debug(s"Receive unhandled message ${o}")
    handled
  }
  def apply(o: Any): Unit = {
    val sender = context.sender
    try { r(o) }
    catch {
      case t: Throwable =>
        val e = CryoError(t)
        sender ! e
        log.error(s"Message ${o} has generated an exception", e)
    }
  }
}
object CryoReceive {
  def apply(r: Actor.Receive)(implicit context: ActorContext): Actor.Receive = r match {
    case _: CryoReceive => r
    case _: Any => CryoReceive(r)
  }
}

object LogDispatcher extends TurboFilter {
  override def decide(marker: Marker, logger: Logger, level: Level, format: String, params: Array[AnyRef], t: Throwable) = {
    val message = MessageFormatter.arrayFormat(format, params).getMessage
    CryoEventBus.publish(Log(level, if (marker == null) "" else marker.toString, message))
    FilterReply.NEUTRAL
  }
}