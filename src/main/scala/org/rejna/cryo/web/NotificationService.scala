package org.rejna.cryo.web

import akka.actor.Props

import spray.routing.RequestContext
import spray.httpx.Json4sSupport
import spray.http.{ ChunkedResponseStart, MessageChunk, ChunkedMessageEnd, HttpResponse, MediaType, HttpEntity }
import spray.can.Http

import org.rejna.cryo.models._

trait NotificationService
  extends ComposableRoute
  with CryoAskSupport
  with Json4sSupport
  with LoggingClass {

  implicit val cryoctx: CryoContext

  val `text/event-stream` = MediaType.custom("text/event-stream")
  addRoute {
    pathPrefix("notification" / Rest) { subscribe }
  }

  def subscribe(subscription: String)(ctx: RequestContext): Unit = {
    actorRefFactory.actorOf {
      Props {
        new CryoActor(cryoctx) {
          ctx.responder ! ChunkedResponseStart(HttpResponse(entity = HttpEntity(`text/event-stream`, "")))
          CryoEventBus.subscribe(self, '/' + subscription)
          log.info(s"Subscribe to ''/${subscription}''")

          def receive = cryoReceive {
            case msg: CryoMessage =>
              log.info(s"Sending message : ${msg}")
              ctx.responder ! MessageChunk("data: " + JsonWithTypeHints.write(msg) + "\n\n")
            case ev: Http.ConnectionClosed =>
              log.warn(s"Stopping response streaming due to ${ev}")
              context.stop(self)
            case e: Any =>
              log.error(s"Unexpected message in NotificationActor : ${e}")
          }
          
          override def postStop() = {
            ctx.responder ! ChunkedMessageEnd
          }
        }
      }
    }
  }
}