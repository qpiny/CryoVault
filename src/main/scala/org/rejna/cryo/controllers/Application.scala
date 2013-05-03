package controllers

import models._;

import scala.collection.mutable.ListBuffer
import scala.util.matching.Regex

import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.iteratee._
import play.api.libs.concurrent._

import play.api.Play.current

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import akka.util.duration._
import akka.event.LoggingReceive

case object GetChannel

object Application extends Controller {

  def index = Action { request =>
    Ok(views.html.glacier(request))
  }

  def socket = WebSocket.async[JsValue] { request => WebSocketActor() }
}

object WebSocketActor {
  lazy val cryo = new Cryo
  def apply() = {
    implicit val timeout = Timeout(10 second)
    val actor = Akka.system.actorOf(Props(new WebSocketActor(cryo)))
    val channel = (actor ? GetChannel)
    channel.mapTo[(play.api.libs.iteratee.Iteratee[JsValue, _], Enumerator[JsValue])].asPromise
  }
}

class WebSocketActor(cryo: Cryo) extends Actor {
  val enumerator = Enumerator.imperative[ResponseEvent]()
  val iteratee = Event.fromJson(Iteratee.foreach[RequestEvent] { event =>
    println("Receive %s".format(event))
    event match {
      case AddIgnoreSubscription(subscription) =>
        ignoreSubscriptions += subscription.r
      case RemoveIgnoreSubscription(subscription) => ignoreSubscriptions -= subscription.r
      case e => cryo.actor.tell(e.withChannel(enumerator), self)
    }
  })
  val channel = (iteratee, enumerator &> Event.toJson)
  var ignoreSubscriptions = ListBuffer[Regex]()

  def receive = {
    case GetChannel => sender ! channel
    case msg: ResponseEvent =>
      println("Sending %s".format(msg.json))
      if (!ignoreSubscriptions.exists(_.findFirstIn(msg.path).isDefined)) enumerator.push(msg) else println("ignored")
    case msg => println("ClientActor has received an unknown message : " + msg)
  }
}