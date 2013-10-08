package org.rejna.cryo.models

import akka.actor.{ Actor, Stash }

class NotificationMock(cryoctx: CryoContext) extends Actor with Stash {
  
  def receive = {
    case MakeActorReady =>
      unstashAll()
      context.become(receiveWhenReady)
    case _ =>
      stash()
  }
  
  def receiveWhenReady: Receive = {
    case GetNotification() =>
      sender ! NotificationGot() 

    case GetNotificationARN() =>
      sender ! NotificationARN("dummy ARN")
  }
}