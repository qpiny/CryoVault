package org.rejna.cryo.models

import scala.collection.mutable.HashMap
import scala.util.matching.Regex

import akka.actor.ActorRef
import akka.event.{ EventBus, SubchannelClassification }
import akka.util.Subclassification

abstract class Event { val path: String }

object CryoEventBus extends EventBus with SubchannelClassification {
  type Event = org.rejna.cryo.models.Event
  type Classifier = String
  type Subscriber = ActorRef

  protected def classify(event: Event) = event.path

  protected def subclassification = new Subclassification[Classifier] {
    def isEqual(x: Classifier, y: Classifier) = x == y
    def isSubclass(x: Classifier, y: Classifier) = x.startsWith(y)
  }

  // FIXME add synchronized + volatile
  protected def publish(event: Event, subscriber: Subscriber): Unit = {
    ignore.get(subscriber) match {
      case Some(filters) if filters.exists(_.findFirstIn(event.path).isDefined) => // ignore
      case _ => subscriber ! event
    }
  }

  private var ignore = HashMap[ActorRef, Set[Regex]]()
  def addIgnoreSubscription(subscriber: Subscriber, subscription: String) = {
    ignore += subscriber -> (ignore.getOrElse(subscriber, Set[Regex]()) + subscription.r)
  }

  def removeIgnoreSubscription(subscriber: Subscriber, subscription: String) = {
    val ignoreSet = ignore.getOrElse(subscriber, Set[Regex]()) - subscription.r
    if (ignoreSet.isEmpty)
      ignore -= subscriber
    else
      ignore += subscriber -> ignoreSet
  }
}