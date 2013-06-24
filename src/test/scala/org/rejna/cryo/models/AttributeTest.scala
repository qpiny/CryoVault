package org.rejna.cryo.models

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.language.postfixOps

object AttributeTest extends App {
  implicit val timeout = 10 seconds

  object AddSimpleCallback extends AttributeSimpleCallback {
    def onChange[A](name: String, previous: A, now: A) = {
      println(s"## AddSimpleCallback(${name}) ${previous} -> ${now}")
      list += now.asInstanceOf[Int]
    }
  }

  object ListCallback extends AttributeListCallback {
    def onListChange[A](name: String, addedValues: List[A], removedValues: List[A]) = {
      println(s"## ListCallback(${name})\n\tadd: ${addedValues}\n\tremovedValues: ${removedValues}")
    }
  }

  object MetaListCallback extends AttributeListCallback {
    def onListChange[A](name: String, addedValues: List[A], removedValues: List[A]) = {
      println(s"(_)(_) ******* MetaListCallback(${name})\n\tadd: ${addedValues}\n\tremovedValues: ${removedValues}")
    }
  }

  val simple = new SimpleAttribute("simple")(0, 0)
  val list = new ListAttribute("list")(List.empty[Int], List.empty[Int])
  val metalist = new MetaListAttribute("metalist", () => {
    println("## start asynchronous metalist computation")
    Future {
      println("## start real metalist computation")
      val a = list.toList map { _ + 1 }
      println(s"## end of real metalist computation => ${a}")
      a
    }
  })

  override def main(args: Array[String]) = {
    super.main(args)
    simple <+> AddSimpleCallback
    list <+> ListCallback
    metalist <* list
    metalist <+> MetaListCallback

    simple() = 10
    simple() = 30
    simple() = 50
  }
}