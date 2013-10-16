package org.rejna.cryo.models

import akka.actor.{ Actor, Stash }

class NotificationMock(cryoctx: CryoContext) extends Actor {
  
  def receive = {
    case MakeActorReady =>
    case PrepareToDie() =>
      sender ! ReadyToDie()
    case GetNotification() =>
      sender ! NotificationGot() 
    case GetNotificationARN() =>
      sender ! NotificationARN("dummy ARN")
  }
}