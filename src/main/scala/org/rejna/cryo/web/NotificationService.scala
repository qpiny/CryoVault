package org.rejna.cryo.web

import scala.collection.mutable.HashMap
import scala.util.{ Try, Success, Failure }
import scala.util.matching.Regex

import akka.actor.{ Props, ActorRef }

import spray.routing.RequestContext
import spray.httpx.Json4sSupport
import spray.http._
import spray.http.HttpHeaders.`Content-Type`
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
    get {
      path("notification") {
        parameter("subscription") { subscription =>
          parameterMultiMap { params =>
            subscribe(subscription, params.getOrElse("except", Nil).map(_.r))
          }
        }
      }
    }
  }

  def subscribe(subscription: String, exceptions: List[Regex])(ctx: RequestContext): Unit = {
    actorRefFactory.actorOf {
      Props {
        new CryoActor(cryoctx) {
          ctx.responder ! ChunkedResponseStart(HttpResponse(headers = `Content-Type`(`text/event-stream`) :: Nil))
          CryoEventBus.subscribe(self, subscription)
          log.info(s"Subscribe to ${subscription}")

          def receive = cryoReceive {
            case evt: Event =>
              if (exceptions.exists(_.findFirstIn(evt.path).isDefined)) {
                log.info(s"Ignoring message path ${evt.path}")
              } else {
                val message = s"event: ${evt.path}\ndata: " + JsonWithTypeHints.write(evt) + "\n\n"
                log.info(s"Sending message : ${message}")
                ctx.responder ! MessageChunk(message)
              }
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