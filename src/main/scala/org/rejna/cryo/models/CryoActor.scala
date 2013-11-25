package org.rejna.cryo.models

import scala.language.implicitConversions
import scala.concurrent.{ ExecutionContext, Future }

import akka.actor.{ Actor, ActorRef }
import akka.pattern.{ ask, AskTimeoutException }

import org.slf4j.{ Logger, MarkerFactory }

trait OptionalMessage

class CryoAskableActorRef(actorName: String, val cryoctx: CryoContext, actorRef: ActorRef)(implicit executionContext: ExecutionContext) {
  
  val log = new SimpleLogger(actorName, cryoctx)
  
  def ?(message: Any) = {
    log.debug(s"${message} - ${actorRef}", marker = Markers.askMsgMarker)
    val timeout = cryoctx.getTimeout(message.getClass)
    actorRef.ask(message)(timeout) map {
      case x =>
        log.debug(s"${message} - ${actorRef} - ${x}", marker = Markers.replyMsgMarker)
        x
    } recover {
      case e =>
        log.error(s"${actorRef} has failed to process message ${message} in ${timeout}", e)
        Future.failed(e)
    } 
  }
}

trait CryoAskSupport {
  implicit def ask(actorRef: ActorRef)(implicit executionContext: ExecutionContext, cryoctx : CryoContext) = new CryoAskableActorRef(getClass.getName, cryoctx, actorRef)
}

trait CryoActorLogger extends LoggingClass { self: Actor =>
  import Markers._
  
  def cryoReceive(f: Actor.Receive) = new Actor.Receive {
    def isDefinedAt(o: Any): Boolean = {
      val handled = f.isDefinedAt(o)
      o match {
        case a: Any if handled => log.debug(s"Receiving message: ${a}", marker = handledMsgMarker)
        case o: OptionalMessage => log.debug(s"Ignored message: ${o}", marker = unhandledMshMarker)
        case t: Throwable => log.warn(s"Unhandled error", marker = errMsgMarker, cause = t)
        case a: Any => log.warn(s"Unhandled message: ${a}", marker = unhandledMshMarker)
      }
      handled
    }
    def apply(o: Any): Unit = {
      val sender = context.sender
      try {
        f(o)
        log.debug(s"${o}", marker = successMsgMarker)
      } catch {
        case t: Throwable =>
          val e = CryoError(s"Message ${o} has generated an error", t)
          sender ! e
          log(e)
      }
    }
  }
}

abstract class CryoActor(_cryoctx: CryoContext) extends Actor with CryoActorLogger with CryoAskSupport {
  implicit val cryoctx = _cryoctx
  implicit val executionContext = context.system.dispatcher
}