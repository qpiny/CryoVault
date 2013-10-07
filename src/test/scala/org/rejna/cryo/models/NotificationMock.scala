package org.rejna.cryo.models

import akka.actor.Actor

class NotificationMock(cryoctx: CryoContext) extends Actor {

  def receive = {
    case GetNotification() =>
      sender ! NotificationGot() 

    case GetNotificationARN() =>
      sender ! NotificationARN("dummy ARN")
  }
}