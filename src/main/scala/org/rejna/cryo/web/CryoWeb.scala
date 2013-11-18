package org.rejna.cryo.web

import scala.collection.mutable.{ HashMap, ListBuffer }
import scala.concurrent.duration._

import akka.actor.{ Props, ActorRef, ActorSystem, PoisonPill, ActorRefFactory }
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout

import com.typesafe.config.{ ConfigFactory, Config }

import spray.can.Http

import org.rejna.cryo.models.{ Glacier, LoggingClass, CryoContext, Event, CryoEventBus, CryoActor }

object CryoWeb {
  println("Cryo is starting ...")
  val config = ConfigFactory.load()
  val system = ActorSystem("cryo", config)
  val cryoctx = new CryoContext(system, config)

  val mainActor = system.actorOf(Props(classOf[CryoWeb], cryoctx), "main")
  val webActor = system.actorOf(Props(classOf[WebServiceActor], cryoctx), "cryo-web-service")
  
  def main(args: Array[String]) = {
    IO(Http)(system).tell(Http.Bind(webActor, interface = "localhost", port = 8080), mainActor)
  }
}

class CryoWeb(cryoctx: CryoContext) extends CryoActor(cryoctx) {
  def receive = cryoReceive {
    case Http.Bound(localAddress) =>
      log.info("Open a few browsers and navigate to http://localhost:8888/html")
      cryoctx.addShutdownHook {
        log.info("Stopping web server")
        cryoctx.status = "Stopped"
        IO(Http)(context.system) ! Http.Unbind
      }
  }
}

