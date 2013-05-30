package org.rejna.cryo.models

import scala.collection.mutable.{ Buffer, Map }
import scala.collection.immutable.{ Map => IMap }
import scala.util.matching.Regex

object AttributePath extends Regex("/cryo/([^/]*)/([^/]*)#(.*)", "service", "object", "attribute")

case class AttributeChange[A](path: String, attribute: ReadAttribute[A]) extends Event
case class AttributeListChange[A](path: String, addedValues: List[A], removedValues: List[A]) extends Event

class AttributeBuilder(path: List[String]) extends LoggingClass {
  object callback extends AttributeChangeCallback {
    override def onChange[A](attribute: ReadAttribute[A]) = {
      log.debug("attribute[%s#%s] change: %s -> %s".format(path.mkString("(", ",", ")"), attribute.name, attribute.previous, attribute.now))
      for (p <- path) CryoEventBus.publish(AttributeChange(p + '#' + attribute.name, attribute))
    }
  }
  object listCallback extends AttributeListCallback {
    override def onListChange[B](attribute: ReadAttribute[List[B]], addedValues: List[B], removedValues: List[B]): Unit = {
      log.debug("attribute[%s#%s] add: %s remove: %s".format(path.mkString("(", ",", ")"), attribute.name, addedValues.take(10), removedValues.take(10)))
      for (p <- path) CryoEventBus.publish(AttributeListChange(p + '#' + attribute.name, addedValues, removedValues))
    }
  }

  def apply[A](name: String, initValue: A) = new Attribute(name, initValue) <+> callback

  def apply[A](name: String, body: () => A) = new MetaAttribute(name, body) <+> callback

  def list[A](name: String, initValue: List[A]) = new ListAttribute[A](name, initValue) <+> listCallback

  def list[A](name: String, body: () => List[A]) = new MetaListAttribute(name, body) <+> listCallback

  def map[A, B](name: String, initValue: IMap[A, B]) = new MapAttribute[A, B](name, initValue) <+> listCallback

  def map[A, B](name: String, body: () => IMap[A, B]) = new MetaMapAttribute(name, body) <+> listCallback

  def /(subpath: String) = AttributeBuilder(path.map { p => s"${p}/${subpath}" }: _*)

  def withAlias(alias: String) = AttributeBuilder(alias :: path: _*)
}
object AttributeBuilder {
  def apply(path: String*) = new AttributeBuilder(path.toList)
}

trait AttributeChangeCallback {
  def onChange[A](attribute: ReadAttribute[A]) = {}
}

trait AttributeListCallback {
  def onListChange[A](attribute: ReadAttribute[List[A]], addedValues: List[A], removedValues: List[A]) = {}
}

trait ReadAttribute[A] {
  protected var callbacks = List[AttributeChangeCallback]()
  val name: String
  def now: A
  def apply() = now
  def previous: A
  def addCallback(attributeCallback: AttributeChangeCallback): this.type = {
    callbacks = attributeCallback +: callbacks
    this
  }
  def <+> = addCallback _
  def removeCallback(attributeCallback: AttributeChangeCallback): this.type = {
    callbacks = callbacks diff List(attributeCallback)
    this
  }
  def <-> = removeCallback _

  def onChange(cb: (=> Unit) => Unit) = {
    lazy val ac: AttributeChangeCallback = new AttributeChangeCallback {
      override def onChange[B](attribute: ReadAttribute[B]): Unit = cb { removeCallback(ac) }
    }
    addCallback(ac)
  }
  
  override def toString = s"${name}(${now})"  
}

class MetaAttribute[A](val name: String, body: () => A) extends ReadAttribute[A] { self =>
  var _now: Option[A] = Some(body())
  var _previous: Option[A] = _now

  object Invalidate extends AttributeChangeCallback {
    override def onChange[C](attribute: ReadAttribute[C]) = {
      if (callbacks.isEmpty) {
        if (_now.isDefined) {
          _previous = _now
          _now = None
        }
      } else {
        _previous = _now
        _now = Some(body())
        if (_previous != _now)
          for (c <- callbacks) c.onChange(self)
      }
    }
  }

  def now = _now.getOrElse {
    val value = body()
    _now = Some(value)
    value
  }

  def previous = _previous.get

  def link[C](attribute: ReadAttribute[C]): MetaAttribute[A] = {
    attribute.addCallback(Invalidate)
    this
  }

  def <*[C] = link[C] _
}

class MetaListAttribute[A](name: String, body: () => List[A])
  extends MetaAttribute[List[A]](name, body)
  with ListCallback[A, MetaListAttribute[A]]

class MetaMapAttribute[A, B](name: String, body: () => IMap[A, B])
  extends MetaAttribute[List[(A, B)]](name, () => body().toList)
  with ListCallback[(A, B), MetaMapAttribute[A, B]]

class Attribute[A](val name: String, initValue: A) extends ReadAttribute[A] { self =>
  private var _now = initValue
  private var _previous = initValue

  def update(newValue: A) = {
    if (_now != newValue) {
      _previous = _now
      _now = newValue
      for (c <- callbacks) c.onChange(this)
    }
  }

  def now = _now
  def now_= = update _
  def previous = _previous
}

trait ListCallback[A, B <: ReadAttribute[List[A]]] { self: B =>
  protected var listCallbacks = List[AttributeListCallback]()

  def addCallback(attributeCallback: AttributeListCallback): B = {
    listCallbacks = attributeCallback +: listCallbacks
    this
  }
  def <+>(attributeCallback: AttributeListCallback) = addCallback(attributeCallback)
  def removeCallback(attributeCallback: AttributeListCallback): B = {
    listCallbacks = listCallbacks diff List(attributeCallback)
    this
  }
  def <->(attributeCallback: AttributeListCallback) = removeCallback(attributeCallback)

  addCallback(new AttributeChangeCallback {
    override def onChange[B](attribute: ReadAttribute[B]): Unit = {
      val add = now diff previous
      val remove = previous diff now
      for (c <- listCallbacks)
        c.onListChange(self, add, remove)
    }
  })

  def onAdd(cb: (=> Unit) => Unit) = {
    lazy val ac: AttributeListCallback = new AttributeListCallback {
      override def onListChange[B](attribute: ReadAttribute[List[B]], addedValues: List[B], removedValues: List[B]): Unit =
        cb { removeCallback(ac) }
    }
    addCallback(ac)
  }

  def onRemove(cb: (=> Unit) => Unit) = {
    lazy val ac: AttributeListCallback = new AttributeListCallback {
      override def onListChange[B](attribute: ReadAttribute[List[B]], addedValues: List[B], removedValues: List[B]): Unit =
        cb { removeCallback(ac) }
    }
    addCallback(ac)
  }
}

class ListAttribute[A](name: String, initValue: List[A])
  extends Attribute[List[A]](name, initValue)
  with Buffer[A]
  with ListCallback[A, ListAttribute[A]] { self =>

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
  extends Attribute[List[(A, B)]](name, initValue.toList)
  with Map[A, B]
  with ListCallback[(A, B), MapAttribute[A, B]] { self =>

  def +=(kv: (A, B)) = {
    update(_addTo(now, kv))
    this
  }
  
  private def _addTo(map: List[(A, B)], kv: (A, B)): List[(A, B)] = {
    val r = map.filterNot(_._1 == kv._1) :+ kv
    r
  }
  
  override def ++=(kvs: TraversableOnce[(A, B)]) = {
    val v = kvs.foldLeft(now) { case (map, element) => _addTo(map, element)}
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