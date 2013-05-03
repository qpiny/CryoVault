package org.rejna.cryo.web

import akka.actor.ActorSystem
import akka.actor.Props

import _root_.org.mashupbots.socko.events.{ HttpResponseStatus, WebSocketHandshakeEvent }
import _root_.org.mashupbots.socko.routes._
import _root_.org.mashupbots.socko.handlers.{ StaticContentHandler, StaticContentHandlerConfig, StaticResourceRequest }
import _root_.org.mashupbots.socko.webserver.WebServer
import _root_.org.mashupbots.socko.webserver.WebServerConfig

object CryoWeb extends App {

  override def main(args: Array[String]) = {
    val system = ActorSystem("cryo")
    
    val staticHandler = system.actorOf(Props(new StaticContentHandler(new StaticContentHandlerConfig)))
    val wsHandler = system.actorOf(Props[CryoSocket])
    
    val routes = Routes({
      case HttpRequest(request) => request match {
        case GET(Path(path)) =>
          staticHandler ! new StaticResourceRequest(request, path)
        case _ => request.response.write(HttpResponseStatus.BAD_REQUEST, "Invalid request")
      }

      case WebSocketHandshake(wsHandshake) => wsHandshake match {
        case Path("/websocket/") =>
          println("Authorize websocket connection")
          wsHandshake.authorize()
      }

      case WebSocketFrame(wsFrame) => {
        println("Register websocket connection")
        wsHandler ! wsFrame
      }

    })

    println("Starting Solver web console ... ")
    if (routes == null) {
      println("Routes is null")
    } else {
      val webServer = new WebServer(WebServerConfig(), routes, system)
      Runtime.getRuntime.addShutdownHook(new Thread {
        override def run { webServer.stop() }
      })
      webServer.start()

      println("Open a few browsers and navigate to http://localhost:8888/html.")
    }
  }
}

