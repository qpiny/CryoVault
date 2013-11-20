package org.rejna.cryo.web

import akka.actor.Props

import spray.routing.RequestContext
import spray.httpx.Json4sSupport
import spray.http._
import spray.can.Http

import org.rejna.cryo.models._

trait NotificationService
  extends ComposableRoute
  with CryoAskSupport
  with Json4sSupport
  with LoggingClass {

  implicit val cryoctx: CryoContext

  val `text/event-stream` = MediaType.custom("text/event-stream")
  MediaTypes.register(`text/event-stream`)
  addRoute {
    pathPrefix("notification" / Rest) { subscribe }
  }

  def subscribe(subscription: String)(ctx: RequestContext): Unit = {
    actorRefFactory.actorOf {
      Props {
        new CryoActor(cryoctx) {
          ctx.responder ! ChunkedResponseStart(HttpResponse(entity = HttpEntity(`text/event-stream`, ""))) //" " * 2048 + "\n\ndata: Start\n\n")))
          CryoEventBus.subscribe(self, '/' + subscription)
          log.info(s"Subscribe to /${subscription}")

          def receive = cryoReceive {
            case msg: CryoMessage =>
              val message = "data: " + JsonWithTypeHints.write(msg) + "\n\n"
              log.info(s"Sending message : ${message}")
              ctx.responder ! MessageChunk(message)
            case ev: Http.ConnectionClosed =>
              log.warn(s"Stopping response streaming due to ${ev}")
              CryoEventBus.unsubscribe(self)
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