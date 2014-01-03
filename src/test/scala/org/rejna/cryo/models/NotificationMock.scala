package org.rejna.cryo.models

import akka.actor.{ Actor, Stash }

class NotificationMock(_cryoctx: CryoContext) extends CryoActor(_cryoctx) {
  
  def receive = {
    case MakeActorReady =>
    case PrepareToDie() =>
      sender ! ReadyToDie()
    case GetNotification() =>
      sender ! Done() 
    case GetNotificationARN() =>
      sender ! NotificationARN("dummy ARN")
  }
}