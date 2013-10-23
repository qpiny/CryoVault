package org.rejna.cryo.web

import scala.collection.mutable.{ HashMap, ListBuffer }

import akka.actor.{ ActorRef, ActorSystem, PoisonPill }
import akka.actor.Props

import com.typesafe.config.ConfigFactory

import org.mashupbots.socko.events.{ HttpResponseStatus, WebSocketHandshakeEvent, WebSocketFrameEvent }
import org.mashupbots.socko.routes._
import org.mashupbots.socko.handlers.{ StaticContentHandler, StaticContentHandlerConfig, StaticResourceRequest }
import org.mashupbots.socko.rest.{ RestRegistry, RestConfig, RestHandler, ReportRuntimeException }
import org.mashupbots.socko.webserver.{ WebServer, WebServerConfig, WebLogConfig }
import org.mashupbots.socko.infrastructure.LocalCache

import org.rejna.cryo.models.{ Glacier, LoggingClass, CryoContext, Event, CryoEventBus }

object CryoWeb extends LoggingClass {
  val config = ConfigFactory.load()
  val system = ActorSystem("cryo", config)
  val cryoctx = new CryoContext(system, config)
  val wsHandlers = HashMap.empty[String, ActorRef]
  val staticHandler = system.actorOf(Props(classOf[StaticContentHandler], StaticContentHandlerConfig(
    cache = new LocalCache(0, 16))), "staticHandler")
  val restRegistry = RestRegistry("org.rejna.cryo.web",
    RestConfig("1.0", "http://localhost:8888/api", reportRuntimeException = ReportRuntimeException.All))
  val restHandler = system.actorOf(Props(classOf[RestHandler], restRegistry), "restHandler") //.withRouter(FromConfig())

  def newRestProcessor(cls: Class[_]) = system.actorOf(Props(cls, cryoctx))

  val routes = Routes({
    case HttpRequest(request) => request match {
      case GET(Path("/exit")) =>
        cryoctx.status = "Stopping"
        log.info("Stopping Cryo")
        request.response.write(HttpResponseStatus.ACCEPTED, "Shutting down cryo ...")
        cryoctx.shutdown
      case GET(Path("/")) =>
        staticHandler ! StaticResourceRequest(request, "webapp/index.html")
      case PathSegments("swagger-ui" :: relativePath) =>
        staticHandler ! new StaticResourceRequest(request, relativePath.mkString("swaggerui/", "/", ""))
      case PathSegments("api" :: _) =>
        restHandler ! request
      case GET(Path(path)) =>
        staticHandler ! StaticResourceRequest(request, "webapp" + path)
      case _: Any => request.response.write(HttpResponseStatus.BAD_REQUEST, "Invalid request")
    }

    case event @ Path("/websocket") => event match {
      case event: WebSocketHandshakeEvent =>
        log.info("Authorize new websocket connection")
        event.authorize() // onComplete = Some(unregisterWebSocket))
      case event: WebSocketFrameEvent =>
        log.info("Receive websocket packet")
        wsHandlers.getOrElseUpdate(event.context.name, registerWebSocket(event)) ! event
        //registerWebSocket(event)
      case o: Any => log.error("Unexpected message : ${o.toString}")
    }

//    case WebSocketFrame(wsFrame) => {
//      log.debug(s"Receive web socket packet : ${wsFrame.readText}")
//      wsHandlers.get(wsFrame.context.name).map(_ ! wsFrame).getOrElse {
//        log.error("Got unregistered websocket frame")
//      }
//    }
    case o: Any => log.error("Unexpected message2 : ${o.toString}")
  })

  def unregisterWebSocket(socketName: String) = {
    log.info(s"Unregister websocket connection: ${socketName}")
    wsHandlers.get(socketName) match {
      case Some(aref) =>
        //aref ! PoisonPill
        wsHandlers -= socketName
      case None =>
        log.warn("Should not happen")
    }
  }
  def registerWebSocket(event: WebSocketFrameEvent) = {
    log.info("Register a new websocket connection")
    //wsHandlers += event.context.name ->
    system.actorOf(Props(classOf[CryoSocket], cryoctx, event))
  }

  def main(args: Array[String]) = {
    log.info("Cryo is starting ...")
    //val webServer = new WebServer(WebServerConfig(webLog = Some(WebLogConfig())), routes, system)
    val webconfig = config.withFallback(ConfigFactory.parseString(s"""web { web-log { custom-actor-path = "${cryoctx.logger.path}", format = "Common" } }"""))
    val webServer = new WebServer(new WebServerConfig(webconfig, "web"), routes, system)
    cryoctx.addShutdownHook {
      log.info("Stopping web server")
      cryoctx.status = "Stopped"
      webServer.stop()
    }
    webServer.start()

    log.info("Open a few browsers and navigate to http://localhost:8888/html")
  }
}

