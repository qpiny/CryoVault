package org.rejna.cryo.models

import akka.actor.{ Actor, DeadLetter }

class DeadLetterMonitor(val cryoctx: CryoContext) extends CryoActor {
  
  override def preStart = {
    context.system.eventStream.subscribe(self, classOf[DeadLetter])
  }
  
  def cryoReceive = {
    case dl: DeadLetter =>
      log.error(s"A message has been lost: ${dl}")
    case o: Any =>
      log.error("Unexpected message", CryoError(o))
  }
}