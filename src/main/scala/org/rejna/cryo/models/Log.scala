package org.rejna.cryo.models

import org.slf4j.{ Marker, LoggerFactory }
import org.slf4j.helpers.MessageFormatter

import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.classic.{ Level, Logger, LoggerContext }
import ch.qos.logback.core.spi.FilterReply

case class Log(level: Level, marker: String, message: String) extends Event {
  val path = Log.levelToPath(level) + '#' + marker
}
object Log {
  LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext].addTurboFilter(LogDispatcher)

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
  lazy val log = Log.getLogger(this.getClass)
}

object LogDispatcher extends TurboFilter {
  override def decide(marker: Marker, logger: Logger, level: Level, format: String, params: Array[AnyRef], t: Throwable) = {
    val message = MessageFormatter.arrayFormat(format, params).getMessage
    CryoEventBus.publish(Log(level, if (marker == null) "" else marker.toString, message))
    FilterReply.ACCEPT
  }
}