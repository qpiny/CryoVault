package org.rejna.util

import collection.mutable.MapBuilder
import collection.generic.CanBuildFrom
import collection.immutable.{ SortedMap, LinearSeq, TreeMap }

class MultiRange[A](val tree: SortedMap[A, A])(implicit val ord: Ordering[A]) extends Iterable[(A, A)] {

  override def iterator = tree.iterator

  val length = tree.size

  def |(begin: A, end: A): MultiRange[A] = {
    if (begin == end)
      return this
    val prefix = tree.to(begin)
    val p: (SortedMap[A, A], A) = prefix.lastOption match {
      case Some(r) => // r._1 <= begin < end
        if (ord.lt(r._2, begin)) // r._1 < r._2 < begin < end
          (prefix, begin)
        else if (ord.lteq(end, r._2)) // r._1 <= begin < end <= r._2
          return new MultiRange(tree)
        else // r._1 <= begin <= r._2 < end
          (prefix.init, r._1)
      case None => (TreeMap.empty[A, A], begin)
    }
    val body = tree.from(end)
    body.headOption match {
      case Some(r) => // end <= r._1 < r._2
        if (ord.lt(end, r._1))
          new MultiRange(p._1.updated(p._2, end) ++ body)
        else // end == r._1 < r._2
          new MultiRange(p._1.updated(p._2, r._2) ++ body.tail)
      case _ => new MultiRange(p._1.updated(p._2, end))
    }
  }

  def &~(begin: A, end: A): MultiRange[A] = {
    //println(toString + " &~ (" + begin + "," + end +")")
    if (begin == end)
      return this
    val prefix = tree.to(begin)

    val p: SortedMap[A, A] = prefix.lastOption match {
      case Some(r) => // r._1 <= begin < end
        if (ord.equiv(r._1, begin)) {
          // r._1 = begin < end
          if (ord.lteq(r._2, end)) // r._1 = begin < r._2 <= end
            prefix - r._1
          //          else if (ord.equiv(r._2, end))      // r._1 = begin < r._2 = end
          //            return MultiRange(tree - r._1)
          else // r._1 = begin < end < r._2
            return new MultiRange(tree - r._1 + (end -> r._2))
        } else {
          // r._1 < begin < end
          if (ord.lteq(r._2, begin)) // r._1 < r._2 <= begin < end
            prefix
          else if (ord.lt(r._2, end)) // r._1 < begin < r._2 < end
            prefix.updated(r._1, begin)
          else if (ord.lt(end, r._2)) // r._1 < begin < end < r._2
            return new MultiRange(tree.updated(r._1, begin) + (end -> r._2))
          else // r._1 < begin < end = r._2
            return new MultiRange(tree.updated(r._1, begin))
        }
      case _ => TreeMap.empty[A, A]
    }

    //println("  ++ prefix=" + prefix)
    //println("  ++ p=" + p)
    //println("  ++ tree.from(end)=" + tree.from(end))

    var suffix = tree.from(begin)
    var finish = false
    while (!finish) {
      suffix.headOption match {
        case Some(r) if ord.lteq(r._2, end) =>
          suffix = suffix.tail
        case _ => finish = true
      }
    }

    suffix.headOption match {
      case Some(r) => // end < r._2
        if (ord.lteq(end, r._1)) // end <= r._1 < r._2
          new MultiRange(p ++ suffix)
        else // r._1 < end < r._2
          new MultiRange(p ++ suffix - r._1 + (end -> r._2))
      case _ =>
        new MultiRange(p)
    }
  }

  def &(sr: MultiRange[A]): MultiRange[A] = MultiRange.intersection(MultiRange.empty[A], iterator, sr.iterator)

  def ||(sr: MultiRange[A]): MultiRange[A] =
    sr.foldLeft(this)((l, i) => l | (i._1, i._2))

  def &&~(sr: MultiRange[A]): MultiRange[A] =
    sr.foldLeft(this)((l, i) => l &~ (i._1, i._2))

  override def isEmpty = tree.isEmpty

  override def hashCode = tree.hashCode

  override def equals(that: Any) = that match {
    case sr: MultiRange[A] => tree.equals(sr.tree)
    case _ => false
  }

  override def filter(f: ((A, A)) => Boolean) = new MultiRange(tree.filter(f))
}

object MultiRange {
  def empty[A](implicit ord: Ordering[A]) = new MultiRange(TreeMap.empty(ord))

  //def apply[A](items: TraversableOnce[(A,A)]) = new MultiRange[A]((new MapBuilder[A,A,MultiRange[A]] ++= items).result)

  def intersection[A](ret: MultiRange[A],
    a: Iterator[(A, A)],
    b: Iterator[(A, A)],
    current_a: Option[(A, A)] = None,
    current_b: Option[(A, A)] = None)(implicit ord: Ordering[A]): MultiRange[A] = {
    //println("intersection: " + ret + " | " + current_a + ", " + current_b)
    var pa = current_a.getOrElse(
      if (a.hasNext) a.next
      else return ret)
    var pb = current_b.getOrElse(
      if (b.hasNext) b.next
      else return ret)

    while (ord.lteq(pa._2, pb._1) || ord.lteq(pb._2, pa._1)) {
      while (ord.lteq(pa._2, pb._1)) {
        if (!a.hasNext)
          return ret
        pa = a.next
      }

      while (ord.lteq(pb._2, pa._1)) {
        if (!b.hasNext)
          return ret
        pb = b.next
      }
    }

    val start = ord.max(pa._1, pb._1)
    if (ord.lt(pa._2, pb._2))
      return intersection(ret | (start, pa._2), a, b, None, Some(pb))
    else
      return intersection(ret | (start, pb._2), a, b, Some(pa), None)
  }
}