package org.rejna.cryo.web

import akka.actor.ActorSystem
import akka.actor.Props

import com.typesafe.config.ConfigFactory

import _root_.org.mashupbots.socko.events.{ HttpResponseStatus, WebSocketHandshakeEvent }
import _root_.org.mashupbots.socko.routes._
import _root_.org.mashupbots.socko.handlers.{ StaticContentHandler, StaticContentHandlerConfig, StaticResourceRequest }
import _root_.org.mashupbots.socko.webserver.{ WebServer, WebServerConfig }
import _root_.org.mashupbots.socko.infrastructure.LocalCache

import org.rejna.cryo.models.{ Glacier, LoggingClass, CryoContext }

object CryoWeb extends App with LoggingClass {

  override def main(args: Array[String]) = {
    val config = ConfigFactory.load()
    val system = ActorSystem("cryo", config)
    val cryoctx = new CryoContext(system, config)
    log.info("Starting cryo ...")

    val staticHandler = system.actorOf(Props(classOf[StaticContentHandler], StaticContentHandlerConfig(
      cache = new LocalCache(0, 16))))
    val wsHandler = system.actorOf(Props[CryoSocket])

    val routes = Routes({
      case HttpRequest(request) => request match {
        case GET(Path("/")) =>
          staticHandler ! new StaticResourceRequest(request, "webapp/glacier.html")
        case GET(Path(path)) =>
          staticHandler ! new StaticResourceRequest(request, "webapp" + path)
        case _ => request.response.write(HttpResponseStatus.BAD_REQUEST, "Invalid request")
      }

      case WebSocketHandshake(wsHandshake) => wsHandshake match {
        case Path("/websocket/") =>
          wsHandshake.authorize()
      }

      case WebSocketFrame(wsFrame) => {
        val wsHandler = system.actorOf(Props[CryoSocket])
        wsHandler ! wsFrame
      }

    })

    val webServer = new WebServer(WebServerConfig(), routes, system)
    Runtime.getRuntime.addShutdownHook(new Thread {
      override def run { webServer.stop() }
    })
    webServer.start()

    log.info("Open a few browsers and navigate to http://localhost:8888/html")
  }
}

