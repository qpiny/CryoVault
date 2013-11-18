package org.rejna.cryo.web

import scala.concurrent.ExecutionContext

import spray.routing.PathMatchers
import spray.httpx.Json4sSupport

import org.rejna.cryo.models._

trait SnapshotService
  extends ComposableRoute
  with CryoAskSupport
  with Json4sSupport
  with CryoExpectableSupport {

  implicit val cryoctx: CryoContext
  implicit val executionContext: ExecutionContext

  val FilePath = Segment.map(_.replace('!', '/'))
  
  addRoute {
    pathPrefix("snapshots") {
      path(PathMatchers.PathEnd) { post { ctx =>
        (cryoctx.inventory ? CreateSnapshot()) expect {
          case SnapshotCreated(snapshotId) => snapshotId
        }
      } } ~
      path("list") { get { ctx =>
        (cryoctx.inventory ? GetSnapshotList()) expect {
          case SnapshotList(_, _, snapshots) => snapshots
        }
      } } ~
      path(Segment) { snapshotId =>
        get { ctx =>
          (cryoctx.datastore ? GetDataStatus(snapshotId)).expect[DataStatus]
        } ~
        delete { ctx =>
          (cryoctx.inventory ? DeleteSnapshot(snapshotId)) expect {
            case SnapshotDeleted(id) => s"OK snapshot ${id} deleted"
          }
        }
      } ~
      pathPrefix(Segment) { snapshotId =>
        path("files" / FilePath) { filepath =>
          get { ctx =>
            (cryoctx.inventory ? SnapshotGetFiles(snapshotId, filepath)) expect {
              case SnapshotFiles(_, _, fe) => fe
            }
          }
        } ~
        path("filter" / FilePath) { filepath =>
          get { ctx =>
            (cryoctx.inventory ? SnapshotGetFilter(snapshotId, filepath)) expect {
              case SnapshotFilter(_, _, filter) => filter
            }
          } ~
          delete { ctx =>
            (cryoctx.inventory ? SnapshotUpdateFilter(snapshotId, filepath, NoOne)) expect {
              case FilterUpdated() => "OK filter removed"
            }
          } ~
          post {
            entity(as[FileFilter]) { filter => ctx =>
              (cryoctx.inventory ? SnapshotUpdateFilter(snapshotId, filepath, filter)) expect {
                case FilterUpdated() => "OK filter updated"
              }
            }
          } ~
          put {
            entity(as[FileFilter]) { filter => ctx =>
              (cryoctx.inventory ? SnapshotUpdateFilter(snapshotId, filepath, filter)) expect {
                case FilterUpdated() => "OK filter updated"
              }
            }
          }
        }
      }
    }
  }
}