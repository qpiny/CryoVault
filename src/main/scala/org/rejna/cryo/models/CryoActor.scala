package org.rejna.cryo.models

import scala.language.implicitConversions
import scala.concurrent.{ ExecutionContext, Future }

import akka.actor.{ Actor, ActorRef }
import akka.pattern.{ ask, AskTimeoutException }

import org.slf4j.Logger

class CryoAskableActorRef(cryoctx: CryoContext, log: Logger, actorRef: ActorRef)(implicit executionContext: ExecutionContext) {
  def ?(message: Any) = {
    log.debug(s"Sending ${message} to ${actorRef}")
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
        case a: Any if handled => log.debug(s"Receive handled message ${a}")
        case t: Throwable => log.info(s"Receive unhandled error", t)
        case a: Any => log.info(s"Receive unhandled message ${a}")
      }
      handled
    }
    def apply(o: Any): Unit = {
      val sender = context.sender
      try {
        f(o)
        log.debug(s"Message ${o} has been successfully handled")
      } catch {
        case t: Throwable =>
          val e = CryoError(s"Message ${o} has generated an exception", t)
          sender ! e
          log.error(e)
      }
    }
  }
}