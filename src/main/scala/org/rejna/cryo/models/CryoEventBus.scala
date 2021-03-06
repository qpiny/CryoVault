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
    override def onChange[A](name: String, previous: Option[A], now: A): Unit = {
      log.debug("attribute[%s#%s] change: %s -> %s".format(path.mkString("(", ",", ")"), name, previous, now))
      for (p <- path) CryoEventBus.publish(AttributeChange(p + '#' + name, previous, now))
    }
  }

  object listCallback extends AttributeListCallback {
    override def onListChange[A](name: String, addedValues: List[A], removedValues: List[A]): Unit = {
      log.debug("attribute[%s#%s] add: %s remove: %s".format(path.mkString("(", ",", ")"), name, addedValues.take(10), removedValues.take(10)))
      for (p <- path) CryoEventBus.publish(AttributeListChange(p + '#' + name, addedValues, removedValues))
    }
  }

//  case class TranslatedCallback(fun: Any => Any, cb: AttributeSimpleCallback) extends AttributeSimpleCallback {
//    override def onChange[A](name: String, previous: Option[A], now: A) =
//      cb.onChange(name, previous.map(fun), fun(now))
//  }
//
//  case class TranslatedListCallback(fun: Any => Any, cb: AttributeListCallback) extends AttributeListCallback {
//    override def onListChange[A](name: String, addedValues: List[A], removedValues: List[A]): Unit =
//      cb.onListChange(name, addedValues.map(fun), removedValues.map(fun))
//  }

  def apply[A](name: String, initValue: A): SimpleAttribute[A] =
    Attribute(name, initValue, None) <+> callback
  def apply[A](name: String, initValue: A, translator: Function1[Any, Any]): SimpleAttribute[A] =
    Attribute(name, initValue, Some(translator)) <+> callback

  def apply[A](name: String, body: () => A)(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute(name, body, None) <+> callback
  def apply[A](name: String, body: () => A, translator: Function1[Any, Any])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute(name, body, Some(translator)) <+> callback

  def future[A](name: String, body: () => Future[A])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.future(name, body, None) <+> callback
  def future[A](name: String, body: () => Future[A], translator: Function1[Any, Any])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.future(name, body, Some(translator)) <+> callback

  def list[A](name: String, initValue: List[A]): ListAttribute[A] =
    Attribute.list(name, initValue, None) <+> listCallback
  def list[A](name: String, initValue: List[A], translator: Function1[Any, Any]): ListAttribute[A] =
    Attribute.list(name, initValue, Some(translator)) <+> listCallback

  def list[A](name: String, body: () => List[A])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.list(name, body, None) <+> listCallback
  def list[A](name: String, body: () => List[A], translator: Function1[Any, Any])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.list(name, body, Some(translator)) <+> listCallback

  def futureList[A](name: String, body: () => Future[List[A]])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.futureList(name, body, None) <+> listCallback
  def futureList[A](name: String, body: () => Future[List[A]], translator: Function1[Any, Any])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.futureList(name, body, Some(translator)) <+> listCallback

  def map[A, B](name: String, initValue: Map[A, B]): MapAttribute[A, B] =
    Attribute.map(name, initValue, None) <+> listCallback
  def map[A, B](name: String, initValue: Map[A, B], translator: Function1[Any, Any]): MapAttribute[A, B] =
    Attribute.map(name, initValue, Some(translator)) <+> listCallback

  def map[A, B](name: String, body: () => Map[A, B])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.map(name, body, None) <+> listCallback
  def map[A, B](name: String, body: () => Map[A, B], translator: Function1[Any, Any])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.map(name, body, Some(translator)) <+> listCallback

  def futureMap[A, B](name: String, body: () => Future[Map[A, B]])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.futureMap(name, body, None) <+> listCallback
  def futureMap[A, B](name: String, body: () => Future[Map[A, B]], translator: Function1[Any, Any])(implicit executionContext: ExecutionContext, timeout: Duration) =
    Attribute.futureMap(name, body, Some(translator)) <+> listCallback

  def /(subpath: Any) = CryoAttributeBuilder(path.map { p => s"${p}/${subpath}" }: _*)

  //def withAlias(alias: String) = CryoAttributeBuilder(alias :: path: _*)
}