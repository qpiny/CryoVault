package models

import akka.actor._
import scala.collection.mutable.{ Buffer, ListBuffer, Map, HashMap }
import scala.collection.immutable.{ Map => IMap }
import play.api.libs.json.JsValue

class AttributeBuilder(val eventStream: CryoEventBus, path: String*) {
  var paths = List(path: _*)

  object callback extends AttributeChangeCallback[JsValue] {
    override def onChange[A](attribute: ReadAttribute[A, JsValue])(implicit serializer: A => JsValue) = {
      println("attribute[%s#%s] change: %s -> %s".format(paths.mkString("(", ",", ")"), attribute.name, attribute.previous, attribute.now))
      for (p <- paths) eventStream.publish(new AttributeChange(p + '#' + attribute.name, attribute))
    }
  }
  object listCallback extends AttributeListCallback[JsValue] {
    override def onListChange[B, C](attribute: ReadAttribute[List[B], JsValue], addedValues: List[C], removedValues: List[C])(implicit serializer: List[C] => JsValue): Unit = {
      println("attribute[%s#%s] add: %s remove: %s".format(paths.mkString("(", ",", ")"), attribute.name, addedValues, removedValues))
      for (p <- paths) eventStream.publish(new AttributeListChange(p + '#' + attribute.name, addedValues, removedValues))
    }
  }

  def apply[A](name: String, initValue: A)(implicit serializer: A => JsValue) = new Attribute(name, initValue) <+> callback

  def apply[A](name: String, body: () => A)(implicit serializer: A => JsValue) = new MetaAttribute(name, body) <+> callback

  def list[A](name: String, initValue: List[A])(implicit serializer: List[A] => JsValue) = new ListAttribute[A, JsValue](name, initValue) <+> listCallback

  def list[A](name: String, body: () => List[A])(implicit serializer: List[A] => JsValue) = new MetaListAttribute(name, body) <+> listCallback

  def map[A, B](name: String, initValue: IMap[A, B])(implicit serializer: List[(A, B)] => JsValue) = new MapAttribute[A, B, JsValue](name, initValue) <+> listCallback

  def map[A, B](name: String, body: () => IMap[A, B])(implicit serializer: List[(A, B)] => JsValue) = new MetaMapAttribute(name, body) <+> listCallback

  def subBuilder(subpath: String) = new AttributeBuilder(eventStream, path.map { p => "%s/%s".format(p, subpath) }: _*)

  def withAlias(path: String) = new AttributeBuilder(eventStream, path :: paths: _*)
}

trait AttributeChangeCallback[S] {
  def onChange[A](attribute: ReadAttribute[A, S])(implicit serializer: A => S) = {}
}

trait AttributeListCallback[S] {
  def onListChange[A, B](attribute: ReadAttribute[List[A], S], addedValues: List[B], removedValues: List[B])(implicit serializer: List[B] => S) = {}
  //def onAdd[A, B](attribute: ReadAttribute[List[A], S], values: List[B])(implicit serializer: List[B] => S) = {}
  //def onRemove[A, B](attribute: ReadAttribute[List[A], S], values: List[B])(implicit serializer: List[B] => S) = {}
}

trait ReadAttribute[A, S] {
  protected var callbacks = List[AttributeChangeCallback[S]]()
  val name: String
  def now: A
  def apply() = now
  def previous: A
  def addCallback(attributeCallback: AttributeChangeCallback[S]): this.type = {
    callbacks = attributeCallback +: callbacks
    this
  }
  def <+> = addCallback _
  def removeCallback(attributeCallback: AttributeChangeCallback[S]): this.type = {
    callbacks = callbacks diff List(attributeCallback)
    this
  }
  def <-> = removeCallback _

  def onChange(cb: (=> Unit) => Unit) = {
    lazy val ac: AttributeChangeCallback[S] = new AttributeChangeCallback[S] {
      override def onChange[B](attribute: ReadAttribute[B, S])(implicit serializer: B => S): Unit = cb { removeCallback(ac) }
    }
    addCallback(ac)
  }
}

class MetaAttribute[A, S](val name: String, body: () => A)(implicit serializer1: A => S) extends ReadAttribute[A, S] { self =>
  var _now: Option[A] = Some(body())
  var _previous: Option[A] = _now

  object Invalidate extends AttributeChangeCallback[S] {
    override def onChange[C](attribute: ReadAttribute[C, S])(implicit serializer2: C => S) = {
      if (_now.isDefined)
        _previous = _now
      _now = None
      for (c <- callbacks) c.onChange(self)
    }
  }

  def now = _now.getOrElse {
    val value = body()
    _now = Some(value)
    value
  }

  def previous = _previous.get

  def link[C](attribute: ReadAttribute[C, S]): MetaAttribute[A, S] = {
    attribute.addCallback(Invalidate)
    this
  }

  def <*[C] = link[C] _
}

class MetaListAttribute[A, S](name: String, body: () => List[A])(implicit val serializer: List[A] => S)
  extends MetaAttribute[List[A], S](name, body)
  with ListCallback[A, MetaListAttribute[A, S], S]

class MetaMapAttribute[A, B, S](name: String, body: () => IMap[A, B])(implicit val serializer: List[(A, B)] => S)
  extends MetaAttribute[List[(A, B)], S](name, () => body().toList)
  with ListCallback[(A, B), MetaMapAttribute[A, B, S], S]

class Attribute[A, S](val name: String, initValue: A)(implicit serializer: A => S) extends ReadAttribute[A, S] { self =>
  private var _now = initValue
  private var _previous = initValue

  def update(newValue: A) = {
    if (_now != newValue) {
      _previous = now
      _now = newValue
      for (c <- callbacks) c.onChange(this)
    }
  }

  def now = _now
  def now_= = update _
  def previous = _previous
}

trait ListCallback[A, B <: ReadAttribute[List[A], S], S] { self: B =>
  protected var listCallbacks = List[AttributeListCallback[S]]()
  val serializer: List[A] => S

  def addCallback(attributeCallback: AttributeListCallback[S]): B = {
    listCallbacks = attributeCallback +: listCallbacks
    this
  }
  def <+>(attributeCallback: AttributeListCallback[S]) = addCallback(attributeCallback)
  def removeCallback(attributeCallback: AttributeListCallback[S]): B = {
    listCallbacks = listCallbacks diff List(attributeCallback)
    this
  }
  def <->(attributeCallback: AttributeListCallback[S]) = removeCallback(attributeCallback)

  addCallback(new AttributeChangeCallback[S] {
    override def onChange[B](attribute: ReadAttribute[B, S])(implicit serializer2: B => S): Unit = {
      val add = now diff previous
      val remove = previous diff now
      for (c <- listCallbacks)
        c.onListChange(self, add, remove)(serializer)      
    }
  })

  def onAdd(cb: (=> Unit) => Unit) = {
    lazy val ac: AttributeListCallback[S] = new AttributeListCallback[S] {
      override def onListChange[B, C](attribute: ReadAttribute[List[B], S], addedValues: List[C], removedValues: List[C])(implicit serializer: List[C] => S): Unit =
        cb { removeCallback(ac) }
    }
    addCallback(ac)
  }

  def onRemove(cb: (=> Unit) => Unit) = {
    lazy val ac: AttributeListCallback[S] = new AttributeListCallback[S] {
      override def onListChange[B, C](attribute: ReadAttribute[List[B], S], addedValues: List[C], removedValues: List[C])(implicit serializer: List[C] => S): Unit = cb { removeCallback(ac) }
    }
    addCallback(ac)
  }
}

class ListAttribute[A, S](name: String, initValue: List[A])(implicit val serializer: List[A] => S)
  extends Attribute[List[A], S](name, initValue)
  with Buffer[A]
  with ListCallback[A, ListAttribute[A, S], S] { self =>

  def remove(n: Int) = {
    val (start, stop) = now splitAt n
    var r = stop.head
    update(start ::: (stop drop 1))
    r
  }

  def insertAll(n: Int, elems: Traversable[A]) = {
    val (start, stop) = now splitAt n
    update(start ::: elems.toList ::: stop)
  }

  def +=(elem: A) = {
    update(now :+ elem)
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

class MapAttribute[A, B, S](name: String, initValue: IMap[A, B])(implicit val serializer: List[(A, B)] => S)
  extends Attribute[List[(A, B)], S](name, initValue.toList)
  with Map[A, B]
  with ListCallback[(A, B), MapAttribute[A, B, S], S] { self =>

  def +=(kv: (A, B)) = {
    update(now.flatMap(a => if (a._1 == kv._1) None else Some(a)) :+ kv)
    this
  }

  def -=(k: A) = {
    update(now.flatMap(kv => if (kv._1 == k) None else Some(kv)))
    this
  }

  def get(k: A) = now.find(_._1 == k).map(_._2)

  def iterator = now.iterator
}