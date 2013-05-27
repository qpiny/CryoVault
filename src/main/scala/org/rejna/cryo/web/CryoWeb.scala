package org.rejna.cryo.web

import scala.collection.mutable.HashMap

import akka.actor.{ ActorRef, ActorSystem, PoisonPill }
import akka.actor.Props

import com.typesafe.config.ConfigFactory

import _root_.org.mashupbots.socko.events.{ HttpResponseStatus, WebSocketHandshakeEvent }
import _root_.org.mashupbots.socko.routes._
import _root_.org.mashupbots.socko.handlers.{ StaticContentHandler, StaticContentHandlerConfig, StaticResourceRequest }
import _root_.org.mashupbots.socko.webserver.{ WebServer, WebServerConfig }
import _root_.org.mashupbots.socko.infrastructure.LocalCache
import org.jboss.netty.channel.Channel

import org.rejna.cryo.models.{ Glacier, LoggingClass, CryoContext }

object CryoWeb extends App with LoggingClass {
  log.info("Starting cryo ...")
  val config = ConfigFactory.load()
  val system = ActorSystem("cryo", config)
  val cryoctx = new CryoContext(system, config)
  val wsHandlers = HashMap.empty[Channel, ActorRef]
  val staticHandler = system.actorOf(Props(classOf[StaticContentHandler], StaticContentHandlerConfig(
    cache = new LocalCache(0, 16))))

  val routes = Routes({
    case HttpRequest(request) => request match {
      case GET(Path("/")) =>
        staticHandler ! new StaticResourceRequest(request, "webapp/glacier.html")
      case GET(Path(path)) =>
        staticHandler ! new StaticResourceRequest(request, "webapp" + path)
      case _: Any => request.response.write(HttpResponseStatus.BAD_REQUEST, "Invalid request")
    }

    case WebSocketHandshake(wsHandshake) => wsHandshake match {
      case Path("/websocket/") =>
        registerWebSocket(wsHandshake)
    }

    case WebSocketFrame(wsFrame) => {
      wsHandlers.get(wsFrame.channel).map(_ ! wsFrame)
    }

  })

  def unregisterWebSocket(channel: Channel) = {
    log.info("Unregister websocket connection")
    wsHandlers.get(channel) match {
      case Some(aref) =>
        //aref ! PoisonPill
        wsHandlers -= channel
      case None =>
        log.warn("Should not happen")
    }
  }
  def registerWebSocket(event: WebSocketHandshakeEvent) = {
    log.info("Register a new websocket connection")
    event.authorize()//onComplete = Some(unregisterWebSocket))
    wsHandlers += event.channel -> system.actorOf(Props(classOf[CryoSocket], cryoctx, event.channel))
  }

  override def main(args: Array[String]) = {
    super.main(args)
    val webServer = new WebServer(WebServerConfig(), routes, system)
    Runtime.getRuntime.addShutdownHook(new Thread {
      override def run { webServer.stop() }
    })
    webServer.start()

    log.info("Open a few browsers and navigate to http://localhost:8888/html")
  }
}

