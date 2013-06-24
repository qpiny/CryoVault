package org.rejna.cryo.models

import scala.language.implicitConversions
import scala.concurrent.{ ExecutionContext, Future }

import akka.actor.{ Actor, ActorRef }
import akka.pattern.{ ask, AskTimeoutException }

import org.slf4j.Logger

class CryoAskableActorRef(cryoctx: CryoContext, log: Logger, actorRef: ActorRef)(implicit executionContext: ExecutionContext) {
  def ?(message: Any) = {
    log.debug(s"[<>] ${message} - ${actorRef}")
    val timeout = cryoctx.getTimeout(message.getClass)
    actorRef.ask(message)(timeout).recover {
      case e =>
        log.error(s"${actorRef} has failed to process message ${message} in ${timeout}", e)
        Future.failed(e)
    }
  }
}

trait CryoActor extends Actor with LoggingClass {
  val cryoctx: CryoContext
  implicit val executionContext = context.system.dispatcher
  implicit def ask(actorRef: ActorRef) = new CryoAskableActorRef(cryoctx, log, actorRef)

  def cryoReceive(f: Actor.Receive) = new Actor.Receive {
    def isDefinedAt(o: Any): Boolean = {
      val handled = f.isDefinedAt(o)
      o match {
        case a: Any if handled => log.debug(s"[>>] ${a}")
        case t: Throwable => log.warn(s"[**]", t)
        case a: Any => log.warn(s"[??] ${a}")
      }
      handled
    }
    def apply(o: Any): Unit = {
      val sender = context.sender
      try {
        f(o)
        log.debug(s"[^^] ${o}")
      } catch {
        case t: Throwable =>
          val e = CryoError(s"[EE] ${o}", t)
          sender ! e
          log.error(e)
      }
    }
  }
}