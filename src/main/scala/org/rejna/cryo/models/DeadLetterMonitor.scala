package org.rejna.cryo.models

import akka.actor.{ Actor, DeadLetter }

class DeadLetterMonitor(cryoctx: CryoContext) extends Actor with LoggingClass {
  
  override def preStart = {
    context.system.eventStream.subscribe(self, classOf[DeadLetter])
  }
  
  def receive = {
    case dl: DeadLetter =>
      log.error(s"A message has been lost: ${dl}")
    case o: Any =>
      log.error("Unexpected message", CryoError(o))
  }
}