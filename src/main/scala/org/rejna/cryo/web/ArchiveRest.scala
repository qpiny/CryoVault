package org.rejna.cryo.web

import scala.util.{ Success, Failure }

import java.util.Date
import java.io.{ PrintWriter, StringWriter }

import akka.actor.{ ActorSystem, Actor, ActorRef }

import org.mashupbots.socko.events.HttpRequestEvent
import org.mashupbots.socko.rest._

import org.json4s.{ Formats, DefaultFormats, NoTypeHints }

import org.rejna.cryo.models._

case class GetArchiveListRequest(context: RestRequestContext) extends RestRequest
case class GetArchiveListResponse(context: RestResponseContext, archiveList: List[DataStatus]) extends RestResponse
object GetArchiveListRegistration extends RestRegistration {
  val method = Method.GET
  val path = "/archives/list"
  val requestParams = Seq.empty
  //override val customFormats = Some(new Formats {
  //    val dateFormat = DefaultFormats.lossless.dateFormat
  //    override val typeHints = NoTypeHints
  //  })
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.newRestProcessor(classOf[ArchiveRestProcessor])
  override val description = "Retrieve list of archives"
}

case class GetArchiveRequest(context: RestRequestContext, archiveId: String) extends RestRequest
case class GetArchiveResponse(context: RestResponseContext, archivet: Option[DataStatus]) extends RestResponse
object GetArchiveRegistration extends RestRegistration {
  val method = Method.GET
  val path = "/archives/{archiveId}"
  val requestParams = Seq(PathParam("archiveId", "ID of archive that needs to be fetched"))
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.newRestProcessor(classOf[ArchiveRestProcessor])
  override val description = "Retrieve specified list information"
}

class ArchiveRestProcessor(_cryoctx: CryoContext) extends CryoActor(_cryoctx) {
  def receive = cryoReceive {
    case GetArchiveListRequest(ctx) =>
      val _sender = sender
      (cryoctx.inventory ? GetArchiveList()) onComplete {
        case Success(al: ArchiveList) => _sender ! GetArchiveListResponse(ctx.responseContext, al.archives)
        case Success(o) => _sender ! GetArchiveListResponse(ctx.responseContext(501, Map("message" -> o.toString)), List.empty[DataStatus])
        case Failure(e) => _sender ! GetArchiveListResponse(ctx.responseContext(500, Map("message" -> e.getMessage)), List.empty[DataStatus])
      }
      context.stop(self)

    case GetArchiveRequest(ctx, archiveId) =>
      val _sender = sender
      (cryoctx.datastore ? GetDataStatus(archiveId)) onComplete {
        case Success(d: DataStatus) => _sender ! GetArchiveResponse(ctx.responseContext, Some(d))
        case Success(DataNotFoundError) => _sender ! GetArchiveResponse(ctx.responseContext(404), None)
        case Success(o) => _sender ! GetArchiveResponse(ctx.responseContext(501, Map("message" -> o.toString)), None)
        case Failure(e) => _sender ! GetArchiveResponse(ctx.responseContext(500, Map("message" -> e.getMessage)), None)
      }
      context.stop(self)
  }
}
