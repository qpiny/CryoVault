package org.rejna.cryo.models

import scala.language.implicitConversions

import akka.actor.{ Actor, ActorContext, ActorRef }

import org.slf4j.{ Marker, LoggerFactory, MarkerFactory }
import org.slf4j.helpers.MessageFormatter
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.classic.{ Level, Logger, LoggerContext }
import ch.qos.logback.core.spi.FilterReply
import org.mashupbots.socko.infrastructure.WebLogEvent

import java.net.InetSocketAddress

case class Log(source: String, level: Level, marker: Option[Marker], message: String, cause: Option[Throwable] = None) extends Event {
  val path = Log.levelToPath(level) + "#" + marker
}
object Log {
  val lc = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
  lc.addTurboFilter(LogDispatcher)

  private def getMarker(markerNames: String*) = {
    val markers = markerNames.map(MarkerFactory.getDetachedMarker)
    val marker = markers.head
    markers.tail.foreach(marker.add)
    marker
  }

  val levelToPath = Map(
    Level.ALL -> "/log",
    Level.TRACE -> "/log/trace",
    Level.DEBUG -> "/log/trace/debug",
    Level.INFO -> "/log/trace/debug/info",
    Level.WARN -> "/log/trace/debug/info/warn",
    Level.ERROR -> "/log/trace/debug/info/warn/error")

  val msgMarker = getMarker("message")
  val askMsgMarker = getMarker("message", "ask")
  val replyMsgMarker = getMarker("message", "reply")
  val errMsgMarker = getMarker("message", "error")
  val unhandledMshMarker = getMarker("message", "unhandled")
  val handledMsgMarker = getMarker("message", "handled")
  val successMsgMarker = getMarker("message", "success")
  val webLogMarker = getMarker("weblog")
}

class SimpleLogger(source: String, cryoctx: CryoContext) {
  def apply(log: Log) = {
    if (cryoctx.logger == null)
      CryoLogger(log)
    else
      cryoctx.logger ! log
  }

  def trace(message: String) = apply(Log(source, Level.TRACE, None, message))
  def debug(message: String) = apply(Log(source, Level.DEBUG, None, message))
  def info(message: String) = apply(Log(source, Level.INFO, None, message))
  def warn(message: String) = apply(Log(source, Level.WARN, None, message))
  def error(message: String) = apply(Log(source, Level.ERROR, None, message))

  def trace(marker: Marker, message: String) = apply(Log(source, Level.TRACE, Some(marker), message))
  def debug(marker: Marker, message: String) = apply(Log(source, Level.DEBUG, Some(marker), message))
  def info(marker: Marker, message: String) = apply(Log(source, Level.INFO, Some(marker), message))
  def warn(marker: Marker, message: String) = apply(Log(source, Level.WARN, Some(marker), message))
  def error(marker: Marker, message: String) = apply(Log(source, Level.ERROR, Some(marker), message))

  def trace(message: String, cause: Throwable) = apply(Log(source, Level.TRACE, None, message, Some(cause)))
  def debug(message: String, cause: Throwable) = apply(Log(source, Level.DEBUG, None, message, Some(cause)))
  def info(message: String, cause: Throwable) = apply(Log(source, Level.INFO, None, message, Some(cause)))
  def warn(message: String, cause: Throwable) = apply(Log(source, Level.WARN, None, message, Some(cause)))
  def error(message: String, cause: Throwable) = apply(Log(source, Level.ERROR, None, message, Some(cause)))

  def trace(marker: Marker, message: String, cause: Throwable) = apply(Log(source, Level.TRACE, Some(marker), message, Some(cause)))
  def debug(marker: Marker, message: String, cause: Throwable) = apply(Log(source, Level.DEBUG, Some(marker), message, Some(cause)))
  def info(marker: Marker, message: String, cause: Throwable) = apply(Log(source, Level.INFO, Some(marker), message, Some(cause)))
  def warn(marker: Marker, message: String, cause: Throwable) = apply(Log(source, Level.WARN, Some(marker), message, Some(cause)))
  def error(marker: Marker, message: String, cause: Throwable) = apply(Log(source, Level.ERROR, Some(marker), message, Some(cause)))

  def trace(e: CryoError) = apply(Log(source, Level.TRACE, Some(e.marker), e.getMessage, Some(e.getCause)))
  def debug(e: CryoError) = apply(Log(source, Level.DEBUG, Some(e.marker), e.getMessage, Some(e.getCause)))
  def info(e: CryoError) = apply(Log(source, Level.INFO, Some(e.marker), e.getMessage, Some(e.getCause)))
  def warn(e: CryoError) = apply(Log(source, Level.WARN, Some(e.marker), e.getMessage, Some(e.getCause)))
  def error(e: CryoError) = apply(Log(source, Level.ERROR, Some(e.marker), e.getMessage, Some(e.getCause)))
}

trait LoggingClass {
  val cryoctx: CryoContext
  lazy val log = new SimpleLogger(getClass.getName, cryoctx)
}

object LogDispatcher extends TurboFilter {
  override def decide(marker: Marker, logger: Logger, level: Level, format: String, params: Array[AnyRef], t: Throwable) = {
    val message =
      (if (format != null) MessageFormatter.arrayFormat(format, params).getMessage else "") +
        (if (t != null) t.getMessage else "")
    if (message != "")
      CryoEventBus.publish(Log(logger.getName, level, Option(marker), message))
    FilterReply.NEUTRAL
  }
}

object CryoLogger {
  def apply(log: Log) = log match {
    case Log(source, Level.TRACE, None, message, None) =>
      LoggerFactory.getLogger(source).trace(message)
    case Log(source, Level.TRACE, Some(marker), message, None) =>
      LoggerFactory.getLogger(source).trace(marker, message)
    case Log(source, Level.TRACE, None, message, Some(cause)) =>
      LoggerFactory.getLogger(source).trace(message, cause)
    case Log(source, Level.TRACE, Some(marker), message, Some(cause)) =>
      LoggerFactory.getLogger(source).trace(marker, message, cause)

    case Log(source, Level.DEBUG, None, message, None) =>
      LoggerFactory.getLogger(source).debug(message)
    case Log(source, Level.DEBUG, Some(marker), message, None) =>
      LoggerFactory.getLogger(source).debug(marker, message)
    case Log(source, Level.DEBUG, None, message, Some(cause)) =>
      LoggerFactory.getLogger(source).debug(message, cause)
    case Log(source, Level.DEBUG, Some(marker), message, Some(cause)) =>
      LoggerFactory.getLogger(source).debug(marker, message, cause)

    case Log(source, Level.INFO, None, message, None) =>
      LoggerFactory.getLogger(source).info(message)
    case Log(source, Level.INFO, Some(marker), message, None) =>
      LoggerFactory.getLogger(source).info(marker, message)
    case Log(source, Level.INFO, None, message, Some(cause)) =>
      LoggerFactory.getLogger(source).info(message, cause)
    case Log(source, Level.INFO, Some(marker), message, Some(cause)) =>
      LoggerFactory.getLogger(source).info(marker, message, cause)

    case Log(source, Level.WARN, None, message, None) =>
      LoggerFactory.getLogger(source).warn(message)
    case Log(source, Level.WARN, Some(marker), message, None) =>
      LoggerFactory.getLogger(source).warn(marker, message)
    case Log(source, Level.WARN, None, message, Some(cause)) =>
      LoggerFactory.getLogger(source).warn(message, cause)
    case Log(source, Level.WARN, Some(marker), message, Some(cause)) =>
      LoggerFactory.getLogger(source).warn(marker, message, cause)

    case Log(source, Level.ERROR, None, message, None) =>
      LoggerFactory.getLogger(source).error(message)
    case Log(source, Level.ERROR, Some(marker), message, None) =>
      LoggerFactory.getLogger(source).error(marker, message)
    case Log(source, Level.ERROR, None, message, Some(cause)) =>
      LoggerFactory.getLogger(source).error(message, cause)
    case Log(source, Level.ERROR, Some(marker), message, Some(cause)) =>
      LoggerFactory.getLogger(source).error(marker, message, cause)
  }
}
class CryoLogger(cryoctx: CryoContext) extends Actor {
  def receive = {
    case log: Log => CryoLogger(log)
    case WebLogEvent(timestamp, serverName, channelId, clientAddress, serverAddress, username, method, uri, requestSize, responseStatusCode, responseSize, timeTaken, protocolVersion, userAgent, referrer) =>
      CryoLogger(Log(serverName, Level.INFO, Some(Log.webLogMarker),
          clientAddress.asInstanceOf[InetSocketAddress].getAddress.getHostAddress + " " +
          username.getOrElse("-") + " " +
          method + " " +
          uri + " " +
          protocolVersion + " " + 
          responseStatusCode + " " +
          responseSize))
    case PrepareToDie() => sender ! ReadyToDie()
    case t: OptionalMessage =>
    case a: Any => println(s"*******************${a.toString}*************")
  }
}