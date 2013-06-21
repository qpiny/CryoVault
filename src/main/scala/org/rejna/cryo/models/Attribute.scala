package org.rejna.cryo.models

import scala.collection.mutable.{ Buffer, Map }
import scala.collection.immutable.{ Map => IMap }
import scala.util.matching.Regex
import scala.concurrent.{ Await, Future, ExecutionContext }
import scala.concurrent.duration._

object AttributePath extends Regex("/cryo/([^/]*)/([^/]*)#(.*)", "service", "object", "attribute")

case class AttributeChange[A](path: String, previous: A, now: A) extends Event
case class AttributeListChange[A](path: String, addedValues: List[A], removedValues: List[A]) extends Event

trait AttributeSimpleCallback {
  def onChange[A](name: String, previous: A, now: A)
}

trait AttributeListCallback {
  def onListChange[A](name: String, addedValues: List[A], removedValues: List[A])
}

object Attribute {
  def apply[A](name: String, initValue: A): SimpleAttribute[A] =
    new SimpleAttribute(name, initValue)

  def apply[A](name: String, body: () => A)(implicit executionContext: ExecutionContext, timeout: Duration) =
    new MetaAttribute(name, () => Future(body()))

  def future[A](name: String, body: () => Future[A])(implicit executionContext: ExecutionContext, timeout: Duration) =
    new MetaAttribute(name, body)

  def list[A](name: String, initValue: List[A]): ListAttribute[A] =
    new ListAttribute[A](name, initValue)

  def list[A](name: String, body: () => List[A])(implicit executionContext: ExecutionContext, timeout: Duration) =
    new MetaListAttribute(name, () => Future(body()))

  def futureList[A](name: String, body: () => Future[List[A]])(implicit executionContext: ExecutionContext, timeout: Duration) =
    new MetaListAttribute(name, body)

  def map[A, B](name: String, initValue: IMap[A, B]): MapAttribute[A, B] =
    new MapAttribute[A, B](name, initValue)

  def map[A, B](name: String, body: () => IMap[A, B])(implicit executionContext: ExecutionContext, timeout: Duration) =
    new MetaMapAttribute(name, () => Future(body()))

  def futureMap[A, B](name: String, body: () => Future[IMap[A, B]])(implicit executionContext: ExecutionContext, timeout: Duration) =
    new MetaMapAttribute(name, body)
}

trait Attribute[A] {
  val name: String
  def now: A
  def apply() = now
  def previous: A
  override def toString = s"${name}(${now})"
}

trait SimpleCallbackable extends LoggingClass {
  private var callbacks = List[AttributeSimpleCallback]()

  def addCallback(attributeCallback: AttributeSimpleCallback): this.type = {
    callbacks = attributeCallback +: callbacks
    this
  }
  def <+>(attributeCallback: AttributeSimpleCallback): this.type = addCallback(attributeCallback)
  def removeCallback(attributeCallback: AttributeSimpleCallback): this.type = {
    callbacks = callbacks diff List(attributeCallback)
    this
  }
  def <->(attributeCallback: AttributeSimpleCallback): this.type = removeCallback(attributeCallback)

  def onChange(cb: (=> Unit) => Unit) = {
    lazy val ac: AttributeSimpleCallback = new AttributeSimpleCallback {
      override def onChange[B](name: String, previous: B, now: B): Unit = cb { removeCallback(ac) }
    }
    addCallback(ac)
  }

  def processCallbacks[A](name: String, previous: A, now: A) = for (c <- callbacks) { c.onChange(name, previous, now) }
}

class MetaAttribute[A](val name: String, body: () => Future[A])(implicit executionContext: ExecutionContext, timeout: Duration)
  extends Attribute[A] with SimpleCallbackable with LoggingClass {
  var value: Future[(A, A)] = body().map(v => (v, v))
  var callbackChain: Future[Unit] = Future.successful()

  value.onFailure { case t => log.error(s"MetaAttribute ${name} computation fails", t) }

  object Invalidate extends AttributeSimpleCallback {
    override def onChange[C](name: String, previous: C, now: C) = invalidate
  }

  def now = Await.result(value, timeout)._2

  def futureNow = value.map(_._2)

  def previous = Await.result(value, timeout)._1

  def futurePrevious = value.map(_._1)

  def link(attribute: SimpleCallbackable): MetaAttribute[A] = {
    attribute <+> Invalidate
    this
  }

  def <*(attribute: SimpleCallbackable): MetaAttribute[A] = link(attribute)

  def invalidate = {
    log.info(s"MetaAttribute ${this.name} has been invalidated")
    // TODO add lock on value (if invalidate method is execute twice in the same time) ?
    value = value flatMap {
      case (p, n) => body().map(x => (n, x))
    } map {
      case (p, n) =>
        if (p != n) {
          callbackChain = callbackChain // lock is not needed here as in value map sequence (not parallel)
            .map { _ => processCallbacks(name, p, n) }
            .recover { case t => log.error(s"MetaAttribute ${name} callback fails", t) }
        }
        (p, n)
    } recoverWith {
      case t =>
        log.error(s"MetaAttribute ${name} computation fails", t)
        body().map(v => (v, v))
    }
  }
}

class MetaListAttribute[A](name: String, body: () => Future[List[A]])(implicit executionContext: ExecutionContext, timeout: Duration)
  extends MetaAttribute[List[A]](name, body)(executionContext, timeout)
  with ListCallbackable[A]

class MetaMapAttribute[A, B](name: String, body: () => Future[IMap[A, B]])(implicit executionContext: ExecutionContext, timeout: Duration)
  extends MetaAttribute[List[(A, B)]](name, () => body().map(_.toList))(executionContext, timeout)
  with ListCallbackable[(A, B)]

class SimpleAttribute[A](val name: String, initValue: A) extends Attribute[A] with SimpleCallbackable {
  private var _now = initValue
  private var _previous = initValue

  def update(newValue: A) = {
    if (_now != newValue) {
      _previous = _now
      _now = newValue
      processCallbacks(name, _previous, _now)
    }
  }

  def now = _now
  def now_= = update _
  def previous = _previous
}

trait ListCallbackable[A] { self: Attribute[List[A]] with SimpleCallbackable =>
  private var listCallbacks = List[AttributeListCallback]()

  // add and remove callback are not thread safe
  def addCallback(attributeCallback: AttributeListCallback): self.type = {
    listCallbacks = attributeCallback +: listCallbacks
    this
  }
  def <+>(attributeCallback: AttributeListCallback): self.type = addCallback(attributeCallback)
  def removeCallback(attributeCallback: AttributeListCallback): self.type = {
    listCallbacks = listCallbacks diff List(attributeCallback)
    this
  }
  def <->(attributeCallback: AttributeListCallback): self.type = removeCallback(attributeCallback)

  addCallback(new AttributeSimpleCallback {
    override def onChange[C](name: String, _previous: C, _now: C): Unit = {
      val n = _now.asInstanceOf[List[A]]
      val p = _previous.asInstanceOf[List[A]]
      val add = n diff p
      val remove = p diff n
      for (c <- listCallbacks)
        c.onListChange(name, add, remove)
    }
  })

  def onAdd(cb: (=> Unit) => Unit) = {
    lazy val ac: AttributeListCallback = new AttributeListCallback {
      override def onListChange[B](name: String, addedValues: List[B], removedValues: List[B]): Unit =
        cb { removeCallback(ac) }
    }
    addCallback(ac)
  }

  def onRemove(cb: (=> Unit) => Unit) = {
    lazy val ac: AttributeListCallback = new AttributeListCallback {
      override def onListChange[B](name: String, addedValues: List[B], removedValues: List[B]): Unit =
        cb { removeCallback(ac) }
    }
    addCallback(ac)
  }
}

class ListAttribute[A](name: String, initValue: List[A])
  extends SimpleAttribute[List[A]](name, initValue)
  with Buffer[A]
  with ListCallbackable[A] {

  def remove(n: Int) = {
    val (start, stop) = now splitAt n
    val h = stop.head
    update(start ::: (stop drop 1))
    h
  }

  def insertAll(n: Int, elems: Traversable[A]) = {
    val (start, stop) = now splitAt n
    update(start ::: elems.toList ::: stop)
  }

  def +=(elem: A) = {
    update(now :+ elem)
    this
  }

  override def ++=(elems: TraversableOnce[A]) = {
    update(now ::: elems.toList)
    this
  }

  def +=:(elem: A) = {
    update(elem +: now)
    this
  }

  def clear = {
    update(List())
  }

  def length = now.length

  def update(n: Int, newelem: A) = {
    val (start, stop) = now splitAt n
    update((start :+ newelem) ::: (stop drop 1))
  }

  def apply(n: Int) = now(n)

  def iterator = now.iterator
}

class MapAttribute[A, B](name: String, initValue: IMap[A, B])
  extends SimpleAttribute[List[(A, B)]](name, initValue.toList)
  with Map[A, B]
  with ListCallbackable[(A, B)] {

  def +=(kv: (A, B)) = {
    update(_addTo(now, kv))
    this
  }

  private def _addTo(map: List[(A, B)], kv: (A, B)): List[(A, B)] = {
    val r = map.filterNot(_._1 == kv._1) :+ kv
    r
  }

  override def ++=(kvs: TraversableOnce[(A, B)]) = {
    val v = kvs.foldLeft(now) { case (map, element) => _addTo(map, element) }
    update(v)
    this
  }

  def -=(k: A) = {
    update(now.filterNot(_._1 == k))
    this
  }

  def get(k: A) = now.find(_._1 == k).map(_._2)

  def iterator = now.iterator
}