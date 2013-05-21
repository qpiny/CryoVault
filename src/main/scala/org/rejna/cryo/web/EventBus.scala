package org.rejna.cryo.web

import scala.util.matching.Regex

import akka.actor.ActorRef
import akka.event.{ EventBus, SubchannelClassification }
import akka.util.Subclassification

import org.mashupbots.socko.events.WebSocketFrameEvent
import org.rejna.cryo.models._

object CryoSocketBus extends CryoEventBus with SubchannelClassification with LoggingClass {
  import EventSerialization._

  type Classifier = String
  type Subscriber = Either[WebSocketFrameEvent, ActorRef]

  protected def classify(event: Event) = event.path
  protected def subclassification = new Subclassification[Classifier] {
    def isEqual(x: Classifier, y: Classifier) = x == y
    def isSubclass(x: Classifier, y: Classifier) = x.startsWith(y)
  }

  private def subscriberId(subscriber: Either[WebSocketFrameEvent, ActorRef]) = subscriber.fold(
    w => w.channel.getId.toString,
    a => a.toString)

  // FIXME add synchronized + volatile
  protected def publish(event: Event, subscriber: Subscriber): Unit = {
    ignore.get(subscriberId(subscriber)) match {
      case Some(filters) if filters.exists(_.findFirstIn(event.path).isDefined) => // ignore
      case _ =>
        subscriber.fold(
          w => w.write(event),
          a => a ! event)
    }
  }

  private var ignore = Map[String, Set[Regex]]()
  def addIgnoreSubscription(subscriber: Subscriber, subscription: String) = {
    ignore = ignore.updated(subscriberId(subscriber),
      ignore.getOrElse(subscriberId(subscriber), Set[Regex]()) + subscription.r)
  }

  def removeIgnoreSubscription(subscriber: Subscriber, subscription: String) = {
    ignore = ignore.updated(subscriberId(subscriber),
      ignore.getOrElse(subscriberId(subscriber), Set[Regex]()) - subscription.r)
  }
}