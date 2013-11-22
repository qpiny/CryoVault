package org.rejna.cryo.models

import akka.actor.{ Actor, DeadLetter, Terminated }

class DeadLetterMonitor(_cryoctx: CryoContext) extends CryoActor(_cryoctx) {
  
  override def preStart = {
    context.system.eventStream.subscribe(self, classOf[DeadLetter])
  }
  
  def receive = cryoReceive {
    case PrepareToDie() =>
      sender ! ReadyToDie()
    case t: Throwable =>
      log.error("Unexpected error", t)
    case DeadLetter(e: Throwable, _, _) =>
      log.error("Unexpected lost error", e)
    case DeadLetter(t: Terminated, _, _) =>
    case dl: DeadLetter =>
      log.error(s"A message has been lost: ${dl}")
    case om: OptionalMessage =>
    case o: Any =>
      log.error(s"Unexpected message ${o}")
  }
}