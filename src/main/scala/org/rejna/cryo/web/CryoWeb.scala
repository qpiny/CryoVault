package org.rejna.cryo.web

import scala.collection.mutable.HashMap

import akka.actor.{ ActorRef, ActorSystem, PoisonPill }
import akka.actor.Props

import com.typesafe.config.ConfigFactory

import org.mashupbots.socko.events.{ HttpResponseStatus, WebSocketHandshakeEvent }
import org.mashupbots.socko.routes._
import org.mashupbots.socko.handlers.{ StaticContentHandler, StaticContentHandlerConfig, StaticResourceRequest }
import org.mashupbots.socko.rest.{ RestRegistry, RestConfig, RestHandler, ReportRuntimeException }
import org.mashupbots.socko.webserver.{ WebServer, WebServerConfig, WebLogConfig }
import org.mashupbots.socko.infrastructure.LocalCache
import org.jboss.netty.channel.Channel

import org.rejna.cryo.models.{ Glacier, LoggingClass, CryoContext }

object CryoWeb extends LoggingClass {
  log.info("Starting cryo ...")
  val config = ConfigFactory.load()
  val system = ActorSystem("cryo", config)
  val cryoctx = new CryoContext(system, config)
  val wsHandlers = HashMap.empty[Channel, ActorRef]
  val staticHandler = system.actorOf(Props(classOf[StaticContentHandler], StaticContentHandlerConfig(
    cache = new LocalCache(0, 16))), "staticHandler")
  val restRegistry = RestRegistry("org.rejna.cryo.web",
    RestConfig("1.0", "http://localhost:8888/data", reportRuntimeException = ReportRuntimeException.All))
  val restHandler = system.actorOf(Props(classOf[RestHandler], restRegistry), "restHandler") //.withRouter(FromConfig())
  //val restProcessor = system.actorOf(Props(classOf[CryoRest], cryoctx), "restProcessor")

  def newRestProcessor(cls: Class[_]) = system.actorOf(Props(cls, cryoctx))
  
  val routes = Routes({
    case HttpRequest(request) => request match {
      case GET(Path("/exit")) =>
        log.info("Stopping Cryo")
        request.response.write(HttpResponseStatus.ACCEPTED, "Shutting down cryo ...")
        cryoctx.shutdown
      case GET(Path("/")) =>
        staticHandler ! StaticResourceRequest(request, "webapp/index.html")
      case PathSegments("swagger-ui" :: relativePath) =>
        staticHandler ! new StaticResourceRequest(request, relativePath.mkString("swaggerui/", "/", ""))
      case GET(PathSegments("data" :: _)) =>
        restHandler ! request
      case GET(Path(path)) =>
        staticHandler ! StaticResourceRequest(request, "webapp" + path)
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
    log.info(s"Unregister websocket connection: ${channel}")
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
    event.authorize() //onComplete = Some(unregisterWebSocket))
    wsHandlers += event.channel -> system.actorOf(Props(classOf[CryoSocket], cryoctx, event.channel))
  }

  def main(args: Array[String]) = {
    val webServer = new WebServer(WebServerConfig(webLog = Some(WebLogConfig())), routes, system)
    cryoctx.addShutdownHook {
      log.info("Stopping web server")
      webServer.stop()
    }
    webServer.start()

    log.info("Open a few browsers and navigate to http://localhost:8888/html")
  }
}

