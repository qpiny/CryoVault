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

object LogDispatcher extends TurboFilter {
  override def decide(marker: Marker, logger: Logger, level: Level, format: String, params: Array[AnyRef], t: Throwable) = {
    val message =
      (if (format != null) MessageFormatter.arrayFormat(format, params).getMessage else "") +
        (if (t != null) t.getMessage else "")
    if (message != "")
      CryoEventBus.publish(Log(level, if (marker == null) "" else marker.toString, message))
    FilterReply.NEUTRAL
  }
}