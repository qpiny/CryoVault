package org.rejna.cryo.web

import scala.concurrent.ExecutionContext

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
    pathPrefix("api" / "archives") {
      path("list") {
        get { implicit ctx =>
          (cryoctx.inventory ? GetArchiveList()) expect {
            case ObjectList(_, archives) => archives
          }
        }
      } ~
        path(Segment) { archiveId =>
          get { implicit ctx =>
            (cryoctx.datastore ? GetDataStatus(archiveId)).expect[DataStatus]
          }
        }
    }
  }
}
