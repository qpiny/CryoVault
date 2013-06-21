package org.rejna.cryo.models

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object FutureTest extends App {

  override def main(args: Array[String]): Unit = {
    var f = Future.successful()
    f = f map { _ => println(1) }
    f = f map { _ => println(2) }
    f = f map { _ => println(3) }
    f = f map { _ => println(4) }
    f = f map { _ => println(5) }
//    f.onComplete {
//      case a => println(a)
//    }
    Thread.sleep(10000)
  }

}