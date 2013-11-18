package org.rejna.cryo.models

import scala.language.implicitConversions
import scala.concurrent.{ ExecutionContext, Future }

import akka.actor.{ Actor, ActorRef }
import akka.pattern.{ ask, AskTimeoutException }

import org.slf4j.{ Logger, MarkerFactory }

trait OptionalMessage

class CryoAskableActorRef(actorName: String, val cryoctx: CryoContext, actorRef: ActorRef)(implicit executionContext: ExecutionContext) extends LoggingClass {
  
  def ?(message: Any) = {
    log.debug(Log.askMsgMarker, s"${message} - ${actorRef}")
    val timeout = cryoctx.getTimeout(message.getClass)
    actorRef.ask(message)(timeout) map {
      case x =>
        log.debug(Log.replyMsgMarker, s"${message} - ${actorRef} - ${x}")
        x
    } recover {
      case e =>
        log.error(s"${actorRef} has failed to process message ${message} in ${timeout}", e)
        Future.failed(e)
    } 
  }
}

trait CryoAskSupport {
  implicit def ask(actorRef: ActorRef)(implicit executionContext: ExecutionContext, cryoctx : CryoContext) = new CryoAskableActorRef(cryoctx, actorRef)
}

trait CryoActorLogger extends LoggingClass { self: Actor =>
  def cryoReceive(f: Actor.Receive) = new Actor.Receive {
    def isDefinedAt(o: Any): Boolean = {
      val handled = f.isDefinedAt(o)
      o match {
        case a: Any if handled => log.debug(Log.handledMsgMarker, s"Receiving message: ${a}")
        case o: OptionalMessage => log.debug(Log.unhandledMshMarker, s"Ignored message: ${o}")
        case t: Throwable => log.warn(Log.errMsgMarker, s"Unhandled error", t)
        case a: Any => log.warn(Log.unhandledMshMarker, s"Unhandled message: ${a}")
      }
      handled
    }
    def apply(o: Any): Unit = {
      val sender = context.sender
      try {
        f(o)
        log.debug(Log.successMsgMarker, s"${o}")
      } catch {
        case t: Throwable =>
          val e = CryoError(s"Message ${o} has generated an error", t)
          sender ! e
          log.error(e)
      }
    }
  }
}

abstract class CryoActor(_cryoctx: CryoContext) extends Actor with CryoActorLogger with CryoAskSupport {
  implicit val cryoctx = _cryoctx
  implicit val executionContext = context.system.dispatcher
}