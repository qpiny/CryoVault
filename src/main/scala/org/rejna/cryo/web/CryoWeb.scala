package org.rejna.cryo.web

import akka.actor.ActorSystem
import akka.actor.Props

import _root_.org.mashupbots.socko.events.{ HttpResponseStatus, WebSocketHandshakeEvent }
import _root_.org.mashupbots.socko.routes._
import _root_.org.mashupbots.socko.handlers.{ StaticContentHandler, StaticContentHandlerConfig, StaticResourceRequest }
import _root_.org.mashupbots.socko.webserver.{ WebServer, WebServerConfig }
import _root_.org.mashupbots.socko.infrastructure.LocalCache

import org.rejna.cryo.models.Cryo

object CryoWeb extends App {

  override def main(args: Array[String]) = {
    val system = Cryo.system

    val staticHandler = system.actorOf(Props(new StaticContentHandler(StaticContentHandlerConfig(
      cache = new LocalCache(0, 16)))))
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
        wsHandler ! wsFrame
      }

    })

    Cryo.setEventBus(CryoSocketBus)
    val webServer = new WebServer(WebServerConfig(), routes, system)
    Runtime.getRuntime.addShutdownHook(new Thread {
      override def run { webServer.stop() }
    })
    webServer.start()

    println("Open a few browsers and navigate to http://localhost:8888/html.")
  }
}

