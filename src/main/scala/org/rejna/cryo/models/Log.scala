package org.rejna.cryo.models

import scala.language.implicitConversions

import akka.actor.{ Actor, ActorContext }

import org.slf4j.{ Marker, LoggerFactory, MarkerFactory }
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
  private def getMarker(markerNames: String*) = {
    val markers = markerNames.map(MarkerFactory.getMarker)
    val marker = markers.head
    markers.tail.foreach(marker.add)
    marker
  }

  def trace(message: String) = Log(Level.TRACE, "", message)
  def debug(message: String) = Log(Level.DEBUG, "", message)
  def info(message: String) = Log(Level.INFO, "", message)
  def warn(message: String) = Log(Level.WARN, "", message)
  def error(message: String) = Log(Level.ERROR, "", message)

  val levelToPath = Map(
    Level.ALL -> "/log",
    Level.TRACE -> "/log/trace",
    Level.DEBUG -> "/log/trace/debug",
    Level.INFO -> "/log/trace/debug/info",
    Level.WARN -> "/log/trace/debug/info/warn",
    Level.ERROR -> "/log/trace/debug/info/warn/error")
  
  val msgMarker = getMarker("message")
  val askMsgMarker = getMarker("message", "ask")
  val errMsgMarker = getMarker("message", "error")
  val unhandledMshMarker = getMarker("message", "unhandled")
  val handledMsgMarker = getMarker("message", "handled")
  val successMsgMarker = getMarker("message", "success")
}

trait LoggingClass {
  lazy implicit val log = Log.getLogger(this.getClass)
  implicit def l2rl(l: org.slf4j.Logger) = RichLog(l)
  case class RichLog(log: org.slf4j.Logger) {
    def apply(l: Log) = l.level match {
      case Level.TRACE => log.trace(l.marker, l.message)
      case Level.DEBUG => log.debug(l.marker, l.message)
      case Level.INFO => log.info(l.marker, l.message)
      case Level.WARN => log.warn(l.marker, l.message)
      case Level.ERROR => log.error(l.marker, l.message)
    }
    def trace(e: CryoError) = log.trace(e.marker, e.getMessage, e.getCause) 
    def debug(e: CryoError) = log.debug(e.marker, e.getMessage, e.getCause)
    def info(e: CryoError) = log.info(e.marker, e.getMessage, e.getCause)
    def warn(e: CryoError) = log.warn(e.marker, e.getMessage, e.getCause)
    def error(e: CryoError) = log.error(e.marker, e.getMessage, e.getCause)
//    def trace(marker: Marker, e: CryoError) = log.trace(marker, e.getMessage, e.getCause) 
//    def debug(marker: Marker, e: CryoError) = log.debug(marker, e.getMessage, e.getCause)
//    def info(marker: Marker, e: CryoError) = log.info(marker, e.getMessage, e.getCause)
//    def warn(marker: Marker, e: CryoError) = log.warn(marker, e.getMessage, e.getCause)
//    def error(marker: Marker, e: CryoError) = log.error(marker, e.getMessage, e.getCause)
  }
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