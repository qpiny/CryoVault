package org.rejna.cryo.web

import scala.util.{ Success, Failure }

import java.util.Date
import java.io.{ PrintWriter, StringWriter }

import akka.actor.{ ActorSystem, Actor, ActorRef }

import org.mashupbots.socko.events.HttpRequestEvent
import org.mashupbots.socko.rest._

import org.json4s.{ Formats, DefaultFormats, NoTypeHints }

import org.rejna.cryo.models._
import InventoryStatus._

case class GetJobListRequest(context: RestRequestContext) extends RestRequest
case class GetJobListResponse(context: RestResponseContext, jobList: List[Job]) extends RestResponse
object GetJobListRegistration extends RestRegistration {
  val method = Method.GET
  val path = "/jobs/list"
  val requestParams = Seq.empty
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.newRestProcessor(classOf[JobRestProcessor])
  override val description = "Retrieve list of jobs"
}

case class GetJobRequest(context: RestRequestContext, jobId: String) extends RestRequest
case class GetJobResponse(context: RestResponseContext, jobt: Option[Job]) extends RestResponse
object GetJobRegistration extends RestRegistration {
  val method = Method.GET
  val path = "/jobs/{jobId}"
  val requestParams = Seq(PathParam("jobId", "ID of job that needs to be fetched"))
  def processorActor(actorSystem: ActorSystem, request: RestRequest): ActorRef = CryoWeb.newRestProcessor(classOf[JobRestProcessor])
  override val description = "Retrieve specified list information"
}

class JobRestProcessor(_cryoctx: CryoContext) extends CryoActor(_cryoctx) {
  def receive = cryoReceive {
    case GetJobListRequest(ctx) =>
      val _sender = sender
      (cryoctx.manager ? GetJobList()) onComplete {
        case Success(al: JobList) => _sender ! GetJobListResponse(ctx.responseContext, al.jobs)
        case Success(o) => _sender ! GetJobListResponse(ctx.responseContext(501, Map("message" -> o.toString)), List.empty[Job])
        case Failure(e) => _sender ! GetJobListResponse(ctx.responseContext(500, Map("message" -> e.getMessage)), List.empty[Job])
      }
      context.stop(self)

    case GetJobRequest(ctx, jobId) =>
      val _sender = sender
      (cryoctx.manager ? GetJob(jobId)) onComplete {
        case Success(jl: JobList) =>
          val j = jl.jobs.find(_.id == jobId)
          val code = if (j.isDefined) 200 else 404
          _sender ! GetJobResponse(ctx.responseContext(code), j)
        case Success(o) => _sender ! GetJobListResponse(ctx.responseContext(501, Map("message" -> o.toString)), List.empty[Job])
        case Failure(e) => _sender ! GetJobListResponse(ctx.responseContext(500, Map("message" -> e.getMessage)), List.empty[Job])
      }
      context.stop(self)
  }
}
