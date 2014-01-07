package org.rejna.cryo.models

import scala.language.implicitConversions
import scala.util.{ Success, Failure }

import akka.actor.{ Actor, ActorContext, ActorRef }
import akka.event.LogSource
import akka.event.Logging._

import org.slf4j.{ Marker, LoggerFactory, MarkerFactory, MDC }
import org.slf4j.helpers.MessageFormatter
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.classic.{ Level, Logger, LoggerContext }
import ch.qos.logback.core.spi.FilterReply

import java.net.InetSocketAddress

sealed trait CryoLog extends Event {
  val logSource: String
  val level: Level
  val marker: Marker
  val message: String
  val cause: Throwable

  lazy val path = CryoLog.levelToPath(level) + "#" + marker
  @transient
  val timestamp = new java.util.Date
}
object CryoLog {
  val lc = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
  lc.addTurboFilter(LogDispatcher)

  def apply(source: String, level: Level, message: String, marker: Marker = Markers.noMarker, cause: Throwable = Error.NoCause) = level match {
    case Level.TRACE => CryoTrace(source, message, marker, cause)
    case Level.DEBUG => CryoDebug(source, message, marker, cause)
    case Level.INFO => CryoInfo(source, message, marker, cause)
    case Level.WARN => CryoWarn(source, message, marker, cause)
    case Level.ERROR => CryoError(source, message, marker, cause)
  }

  val levelToPath = Map(
    Level.ALL -> "/log",
    Level.TRACE -> "/log/trace",
    Level.DEBUG -> "/log/trace/debug",
    Level.INFO -> "/log/trace/debug/info",
    Level.WARN -> "/log/trace/debug/info/warn",
    Level.ERROR -> "/log/trace/debug/info/warn/error")
}

object Markers {
  val noMarker = getMarker("")
  val msgMarker = getMarker("message")
  val askMsgMarker = getMarker("message", "ask")
  val replyMsgMarker = getMarker("message", "reply")
  val errMsgMarker = getMarker("message", "error")
  val unhandledMshMarker = getMarker("message", "unhandled")
  val handledMsgMarker = getMarker("message", "handled")
  val successMsgMarker = getMarker("message", "success")
  val webLogMarker = getMarker("weblog")

  private def getMarker(markerNames: String*): Marker = {
    val markers = markerNames.map(MarkerFactory.getDetachedMarker)
    val marker = markers.head
    markers.tail.foreach(marker.add)
    marker
  }
}

case class CryoTrace(logSource: String, message: String, marker: Marker = Markers.noMarker, cause: Throwable = Error.NoCause) extends CryoLog { val level = Level.TRACE }
case class CryoDebug(logSource: String, message: String, marker: Marker = Markers.noMarker, cause: Throwable = Error.NoCause) extends CryoLog { val level = Level.DEBUG }
case class CryoInfo(logSource: String, message: String, marker: Marker = Markers.noMarker, cause: Throwable = Error.NoCause) extends CryoLog { val level = Level.INFO }
case class CryoWarn(logSource: String, message: String, marker: Marker = Markers.noMarker, cause: Throwable = Error.NoCause) extends CryoLog { val level = Level.WARN }
case class CryoError(logSource: String, message: String, marker: Marker = Markers.errMsgMarker, cause: Throwable = Error.NoCause) extends Exception with CryoLog { val level = Level.ERROR }
abstract class GenericError extends Exception with CryoLog { val level = Level.ERROR; val marker= Markers.noMarker }

//object CryoError {
//  private def apply(message: String, marker: Marker, a: Any): CryoError = a match {
//    case Failure(e) => CryoError(s"${message}: failure", marker, e)
//    case Success(e) => CryoError(s"${message}: success", marker, e)
//    case e: CryoError => e
//    case e: Throwable => new CryoError("FIXME : no source", message, marker, e)
//    case e: Any => new CryoError("FIXME : no source", s"${message}: unexpected message: ${e}", marker)
//  }
//  //def apply(source: String, message: String, marker: Marker, cause: Throwable) = new CryoError(source, message, marker, cause)
//  def apply[T](message: String, a: Any)(implicit source: LogSource[T]): CryoError = {
//    a match {
//      case e: CryoError => e.copy(message = message + ":" + e.message)
//      case e: Any => CryoError(message, Markers.errMsgMarker, e)
//    }
//  }
//  def apply(message: String): CryoError = new CryoError(org.slf4j.Logger.ROOT_LOGGER_NAME, message)
//}

trait ErrorGenerator {
  implicit val logSource = getClass.getName
  
  def cryoError(message: String, a: Any): CryoError = {
    a match {
      case e: CryoError => e.copy(message = s"${message} : ${e.message}")
      case Failure(e) => cryoError(s"${message} (failure)", e)
      case Success(e) => cryoError(s"${message} (success)", e)
      case e: Throwable => new CryoError(logSource, message, Markers.errMsgMarker, e)
      case e: Any => new CryoError(logSource, s"${message} : unexpected message: ${e}", Markers.errMsgMarker)
    }
  }
}

class SimpleLogger(source: String, cryoctx: CryoContext) {
  import Markers._
  @inline
  final def apply(log: CryoLog) = {
    if (cryoctx.logger == null)
      CryoLogger(log)
    else
      cryoctx.logger ! log
  }

  def trace(message: String, marker: Marker = noMarker, cause: Throwable = Error.NoCause) = apply(CryoTrace(source, message, marker, cause))
  def debug(message: String, marker: Marker = noMarker, cause: Throwable = Error.NoCause) = apply(CryoDebug(source, message, marker, cause))
  def info(message: String, marker: Marker = noMarker, cause: Throwable = Error.NoCause) = apply(CryoInfo(source, message, marker, cause))
  def warn(message: String, marker: Marker = noMarker, cause: Throwable = Error.NoCause) = apply(CryoWarn(source, message, marker, cause))
  def error(message: String, marker: Marker = noMarker, cause: Throwable = Error.NoCause) = apply(CryoError(source, message, marker, cause))

  def trace(message: String, cause: Throwable) = apply(CryoTrace(source, message, noMarker, cause))
  def debug(message: String, cause: Throwable) = apply(CryoDebug(source, message, noMarker, cause))
  def info(message: String, cause: Throwable) = apply(CryoInfo(source, message, noMarker, cause))
  def warn(message: String, cause: Throwable) = apply(CryoWarn(source, message, noMarker, cause))
  def error(message: String, cause: Throwable) = apply(CryoError(source, message, noMarker, cause))

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
      CryoEventBus.publish(CryoLog(logger.getName, level, message, marker))
    FilterReply.NEUTRAL
  }
}

object CryoLogger {

  val mdcThreadAttributeName = "sourceThread"
  val mdcAkkaSourceAttributeName = "akkaSource"
  val mdcAkkaTimestamp = "akkaTimestamp"

  @inline
  final def withMdc(log: CryoLog)(logStatement: => Unit) {
    //MDC.put(mdcAkkaSourceAttributeName, logSource)
    //MDC.put(mdcThreadAttributeName, logEvent.thread.getName)
    //MDC.put(mdcAkkaTimestamp, formatTimestamp(logEvent.timestamp))
    try logStatement finally {
      MDC.remove(mdcAkkaSourceAttributeName)
      MDC.remove(mdcThreadAttributeName)
      MDC.remove(mdcAkkaTimestamp)
    }
  }

  @inline
  final def withMdc(log: LogEvent)(logStatement: => Unit) {
    //MDC.put(mdcAkkaSourceAttributeName, logSource)
    //MDC.put(mdcThreadAttributeName, logEvent.thread.getName)
    //MDC.put(mdcAkkaTimestamp, formatTimestamp(logEvent.timestamp))
    try logStatement finally {
      MDC.remove(mdcAkkaSourceAttributeName)
      MDC.remove(mdcThreadAttributeName)
      MDC.remove(mdcAkkaTimestamp)
    }
  }

  def apply(e: CryoLog) = {
    val logger = LoggerFactory.getLogger(e.logSource)
    val cause = if (e.cause == Error.NoCause) null else e.cause
    e match {
      case l: CryoTrace => withMdc(l)(logger.trace(l.marker, l.message, cause))
      case l: CryoDebug => withMdc(l)(logger.debug(l.marker, l.message, cause))
      case l: CryoInfo => withMdc(l)(logger.info(l.marker, l.message, cause))
      case l: CryoWarn => withMdc(l)(logger.warn(l.marker, l.message, cause))
      case l: CryoError => withMdc(l)(logger.error(l.marker, l.message, cause))
      case l: GenericError => withMdc(l)(logger.error(l.marker, l.message, cause))
    }
  }

  def apply(e: LogEvent) = e match {
    case l: Debug => withMdc(l)(LoggerFactory.getLogger(l.logSource).debug("{}", l.message))
    case l: Info => withMdc(l)(LoggerFactory.getLogger(l.logSource).info("{}", l.message))
    case l: Warning => withMdc(l)(LoggerFactory.getLogger(l.logSource).warn("{}", l.message))
    case l: Error => withMdc(l)(LoggerFactory.getLogger(l.logSource).error("{}", l.message, l.cause))
  }
}

class CryoLogger(cryoctx: CryoContext) extends Actor {
  def receive = {
    case e: CryoLog => CryoLogger(e)
    case e: LogEvent => CryoLogger(e)
    case InitializeLogger(_) => sender ! LoggerInitialized
    case PrepareToDie() => sender ! ReadyToDie()
    case t: OptionalMessage =>
    case a: Any => println(s"*******************${a.toString}*************")
  }

}