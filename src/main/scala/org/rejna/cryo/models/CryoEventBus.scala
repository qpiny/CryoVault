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

trait Event extends CryoMessage { val path: String }
trait Request extends CryoMessage
trait Response extends CryoMessage

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

object CryoAttributeBuilder {
  def apply(path: String*)(implicit cryoctx: CryoContext) = new CryoAttributeBuilder(path.toList)
}

class CryoAttributeBuilder(path: List[String])(implicit val cryoctx: CryoContext) extends LoggingClass {
  object callback extends AttributeSimpleCallback {
    override def onChange[A](name: String, previous: Option[A], now: A) = {
      log.debug("attribute[%s#%s] change: %s -> %s".format(path.mkString("(", ",", ")"), name, previous, now))
      for (p <- path) CryoEventBus.publish(AttributeChange(p + '#' + name, previous, now))
    }
  }
  object listCallback extends AttributeListCallback {
    override def onListChange[B](name: String, addedValues: List[B], removedValues: List[B]): Unit = {
      log.debug("attribute[%s#%s] add: %s remove: %s".format(path.mkString("(", ",", ")"), name, addedValues.take(10), removedValues.take(10)))
      for (p <- path) CryoEventBus.publish(AttributeListChange(p + '#' + name, addedValues, removedValues))
    }
  }

  def apply[A](name: String, initValue: A): SimpleAttribute[A] =
    Attribute(name, initValue) <+> callback

  def apply[A](name: String, body: () => A)(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute(name, body) <+> callback

  def future[A](name: String, body: () => Future[A])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.future(name, body) <+> callback

  def list[A](name: String, initValue: List[A]): ListAttribute[A] =
    Attribute.list(name, initValue) <+> listCallback

  def list[A](name: String, body: () => List[A])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.list(name, body) <+> listCallback

  def futureList[A](name: String, body: () => Future[List[A]])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.futureList(name, body) <+> listCallback

  def map[A, B](name: String, initValue: Map[A, B]): MapAttribute[A, B] =
    Attribute.map(name, initValue) <+> listCallback

  def map[A, B](name: String, body: () => Map[A, B])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.map(name, body) <+> listCallback

  def futureMap[A, B](name: String, body: () => Future[Map[A, B]])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.futureMap(name, body) <+> listCallback

  def /(subpath: Any) = CryoAttributeBuilder(path.map { p => s"${p}/${subpath}" }: _*)

  //def withAlias(alias: String) = CryoAttributeBuilder(alias :: path: _*)
}