package org.rejna.cryo.models

import akka.actor.ActorSystem
import akka.testkit.{ TestKit, TestActorRef }
import org.scalatest.WordSpec

class SnapshotTest extends TestKit(ActorSystem()) {
  val snapshot = system.actorOf(Props)
  
}