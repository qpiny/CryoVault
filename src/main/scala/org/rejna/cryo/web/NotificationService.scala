package org.rejna.cryo.web

import akka.actor.Props

import spray.routing.RequestContext
import spray.httpx.Json4sSupport
import spray.http.{ ChunkedResponseStart, MessageChunk, HttpResponse }
import spray.can.Http

import org.rejna.cryo.models._

trait NotificationService
  extends ComposableRoute
  with CryoAskSupport
  with Json4sSupport
  with CryoExpectableSupport {

  implicit val cryoctx: CryoContext

  addRoute {
    pathPrefix("notification" / Rest) { subscribe }

  }

  def subscribe(subscription: String)(ctx: RequestContext): Unit = {
    actorRefFactory.actorOf {
      Props {
        new CryoActor(cryoctx) {
          ctx.responder ! ChunkedResponseStart(HttpResponse())
          CryoEventBus.subscribe(self, subscription)

          def receive = cryoReceive {
            case msg: CryoMessage =>
              log.debug(s"Sending message : ${msg}")
              ctx.responder ! MessageChunk(JsonWithTypeHints.write(msg))
            case ev: Http.ConnectionClosed =>
              log.warn(s"Stopping response streaming due to ${ev}")
              context.stop(self)
            case e: Any =>
              log.error(s"Unexpected message in NotificationActor : ${e}")
          }
        }
      }
    }
  }
}