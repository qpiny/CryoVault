package org.rejna.cryo.models

import scala.collection.mutable.HashMap
import scala.collection.immutable.Map
import scala.util.matching.Regex
import scala.util.{ Failure, Success }
import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.duration._

import akka.actor.ActorRef
import akka.event.{ EventBus, SubchannelClassification }
import akka.util.Subclassification

import org.slf4j.Marker

trait CryoMessage

trait Event extends CryoMessage {
  val path: EventPath
  val classifier = path.classifier
}
trait Request extends CryoMessage
trait Response extends CryoMessage

object CryoEventBus extends EventBus with SubchannelClassification {
  type Event = org.rejna.cryo.models.Event
  type Classifier = String
  type Subscriber = ActorRef

  protected def classify(event: Event) = event.classifier

  protected def subclassification = new Subclassification[Classifier] {
    def isEqual(x: Classifier, y: Classifier) = x == y
    def isSubclass(x: Classifier, y: Classifier) = x.startsWith(y)
  }

  protected def publish(event: Event, subscriber: Subscriber): Unit = {
    subscriber ! event
  }
}

object EventPath {
  def apply(path: String*)(implicit cryoctx: CryoContext) = new CryoAttributeBuilder(path.toList)
}

class EventPath(path: List[String])(implicit val cryoctx: CryoContext) extends LoggingClass {
  val classifier = path.mkString(".") + '#' + name
  
  object callback extends AttributeSimpleCallback {
    override def onChange[A](eventPath: EventPath, previous: Option[A], now: A) = {
      log.debug("attribute[%s#%s] change: %s -> %s".format(path.mkString("(", ",", ")"), name, previous, now))
      CryoEventBus.publish(AttributeChange(eventPath, previous, now))
    }
  }
  object listCallback extends AttributeListCallback {
    override def onListChange[B](eventPath: EventPath, addedValues: List[B], removedValues: List[B]): Unit = {
      log.debug("attribute[%s#%s] add: %s remove: %s".format(path.mkString("(", ",", ")"), name, addedValues.take(10), removedValues.take(10)))
      CryoEventBus.publish(AttributeListChange(eventPath, addedValues, removedValues))
    }
  }

  def apply[A](eventPath: EventPath, initValue: A): SimpleAttribute[A] =
    Attribute(eventPath, initValue) <+> callback

  def apply[A](eventPath: EventPath, body: () => A)(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute(eventPath, body) <+> callback

  def future[A](eventPath: EventPath, body: () => Future[A])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.future(eventPath, body) <+> callback

  def list[A](eventPath: EventPath, initValue: List[A]): ListAttribute[A] =
    Attribute.list(eventPath, initValue) <+> listCallback

  def list[A](eventPath: EventPath, body: () => List[A])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.list(eventPath, body) <+> listCallback

  def futureList[A](eventPath: EventPath, body: () => Future[List[A]])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.futureList(eventPath, body) <+> listCallback

  def map[A, B](eventPath: EventPath, initValue: Map[A, B]): MapAttribute[A, B] =
    Attribute.map(eventPath, initValue) <+> listCallback

  def map[A, B](eventPath: EventPath, body: () => Map[A, B])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.map(eventPath, body) <+> listCallback

  def futureMap[A, B](eventPath: EventPath, body: () => Future[Map[A, B]])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.futureMap(eventPath, body) <+> listCallback

  def /(subpath: String) = new EventPath(subpath :: path, name)

  //def withAlias(alias: String) = CryoAttributeBuilder(alias :: path: _*)
}