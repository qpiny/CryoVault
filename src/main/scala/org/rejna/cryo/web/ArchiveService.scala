package org.rejna.cryo.web

import scala.concurrent.ExecutionContext

import spray.routing.PathMatchers
import spray.httpx.Json4sSupport

import org.rejna.cryo.models._

trait ArchiveService
  extends ComposableRoute
  with CryoAskSupport
  with Json4sSupport
  with CryoExpectableSupport {
  
  implicit val cryoctx: CryoContext
  implicit val executionContext: ExecutionContext

  addRoute {
    pathPrefix("archives") {
      path("list") { get { ctx =>
        (cryoctx.inventory ? GetArchiveList()) expect {
          case ArchiveList(_, _, archives) => archives
        }
     } } ~
     path(Segment) { archiveId =>
       get { ctx =>
         (cryoctx.datastore ? GetDataStatus(archiveId)).expect[DataStatus]
       }
     }
    }
  }
}

