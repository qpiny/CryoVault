package org.rejna.cryo.models

import scala.collection.mutable.HashMap
import scala.util.matching.Regex
import scala.util.{ Failure, Success }

import akka.actor.ActorRef
import akka.event.{ EventBus, SubchannelClassification }
import akka.util.Subclassification

trait CryoMessage

abstract class Event extends CryoMessage { val path: String }
abstract class Request extends CryoMessage
abstract class Response extends CryoMessage
class CryoError(message: String, cause: Throwable = null) extends Exception(message, cause) with CryoMessage
object CryoError {
  def apply(message: String, a: Any): CryoError = a match {
    //case e: CryoError => e
    case Failure(e) => CryoError(s"${message}: failure", e)
    case Success(e) => CryoError(s"${message}: success", e)
    case e: Throwable => new CryoError(message, e)
    case e: Any => new CryoError(s"${message}: unexpected message: ${e}")
  }
}

object CryoEventBus extends EventBus with SubchannelClassification {
  type Event = org.rejna.cryo.models.Event
  type Classifier = String
  type Subscriber = ActorRef

  protected def classify(event: Event) = event.path

  protected def subclassification = new Subclassification[Classifier] {
    def isEqual(x: Classifier, y: Classifier) = x == y
    def isSubclass(x: Classifier, y: Classifier) = x.startsWith(y)
  }

  protected def publish(event: Event, subscriber: Subscriber): Unit = {
    subscriber ! event
  }
}