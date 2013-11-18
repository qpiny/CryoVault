package org.rejna.cryo.web

import scala.concurrent.ExecutionContext

import spray.routing.PathMatchers
import spray.httpx.Json4sSupport

import org.rejna.cryo.models._

trait JobService
  extends ComposableRoute
  with CryoAskSupport
  with Json4sSupport
  with CryoExpectableSupport {

  implicit val cryoctx: CryoContext
  implicit val executionContext: ExecutionContext

  addRoute {
    pathPrefix("api" / "jobs") {
      path("list") {
        get { implicit ctx =>
          (cryoctx.manager ? GetJobList()) expect {
            case JobList(jobs) => jobs
          }
        }
      } ~
        path(Segment) { jobId =>
          get { implicit ctx =>
            (cryoctx.manager ? GetJob(jobId)).expect[Job]
          }
        }
    }
  }
}