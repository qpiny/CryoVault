package org.rejna.cryo.web

import scala.collection.mutable.HashMap
import scala.util.{ Try, Success, Failure }

import akka.actor.{ Props, ActorRef }

import spray.routing.RequestContext
import spray.httpx.Json4sSupport
import spray.http._
import spray.http.HttpHeaders.`Content-Type`
import spray.can.Http
import spray.http.StatusCodes.ServerError

import java.util.UUID

import org.rejna.cryo.models._

trait NotificationService
  extends ComposableRoute
  with CryoAskSupport
  with Json4sSupport
  with LoggingClass {

  implicit val cryoctx: CryoContext

  val `text/event-stream` = MediaType.custom("text/event-stream")
  MediaTypes.register(`text/event-stream`)

  val notificationActors = HashMap.empty[UUID, ActorRef]

  addRoute {
    get { path("notification" / Rest) { subscribe } } ~
      post {
        pathPrefix(JavaUUID) { nid =>
          path("ignore" / Rest) { ignore =>
            ctx =>
              notificationActors.get(nid) match {
                case Some(aref) =>
                  aref ! "plop"
                  ctx.complete("ok")
                case None =>
                  ctx.withHttpResponseMapped(_.copy(status = InternalServerError)).complete("Error")
              }
          }
        }
      }
    //anyParams { complete("pl") }
  }

  def subscribe(subscription: String)(ctx: RequestContext): Unit = {
    val nid = UUID.randomUUID
    val aref = actorRefFactory.actorOf {
      Props {
        new CryoActor(cryoctx) {
          ctx.responder ! ChunkedResponseStart(HttpResponse(headers = `Content-Type`(`text/event-stream`) :: Nil))
          CryoEventBus.subscribe(self, '/' + subscription)
          log.info(s"Subscribe to /${subscription}")

          def receive = cryoReceive {
            case evt: Event =>
              val message = s"event: ${evt.path}\ndata: " + JsonWithTypeHints.write(evt) + "\n\n"
              log.info(s"Sending message : ${message}")
              ctx.responder ! MessageChunk(message)
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
            notificationActors -= nid
          }
        }
      }
    }
    notificationActors += nid -> aref
  }
}