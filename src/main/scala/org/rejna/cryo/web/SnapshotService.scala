package org.rejna.cryo.web

import scala.concurrent.ExecutionContext

import spray.httpx.Json4sSupport
import spray.routing.Directive1

import org.json4s.JString

import org.rejna.cryo.models._

trait SnapshotService
  extends ComposableRoute
  with CryoAskSupport
  with Json4sSupport
  with CryoExpectableSupport {

  implicit val cryoctx: CryoContext
  implicit val executionContext: ExecutionContext

  val filePath = Segment.map(_.replace('!', '/'))
//  val requestBody = extract(_.request.entity.asString)
//  val fileFilter = requestBody.flatMap(ff => FileFilterParser.parse(ff).fold[Directive1[FileFilter]](x => {
//    println(s"DEBUG>>>> Parse error |${ff}|=>|${x}|")
//    reject
//  }, x => provide(x)))

  addRoute {
    pathPrefix("api" / "snapshots") {
      pathEndOrSingleSlash {
        post { implicit ctx =>
          (cryoctx.inventory ? CreateSnapshot()) expect {
            case SnapshotCreated(snapshotId) => snapshotId
          }
        }
      } ~
        path("list") {
          get { implicit ctx =>
            (cryoctx.inventory ? GetSnapshotList()) expect {
              case SnapshotList(_, _, snapshots) => snapshots
            }
          }
        } ~
        path(Segment) { snapshotId =>
          get { implicit ctx =>
            (cryoctx.datastore ? GetDataStatus(snapshotId)).expect[DataStatus]
          } ~
            delete { implicit ctx =>
              (cryoctx.inventory ? DeleteSnapshot(snapshotId)) expect {
                case SnapshotDeleted(id) => s"OK snapshot ${id} deleted"
              }
            }
        } ~
        pathPrefix(Segment) { snapshotId =>
          pathPrefix("files") {
            get { implicit ctx =>
              val path = ctx.unmatchedPath.toString.dropWhile(_ == '/').replace("!", "/")
              (cryoctx.inventory ? SnapshotGetFiles(snapshotId, path)) expect {
                case SnapshotFiles(_, _, fe) => fe
              }
            }
          } ~
            path("filter" / filePath) { filepath =>
              get { implicit ctx =>
                (cryoctx.inventory ? SnapshotGetFilter(snapshotId, filepath)) expect {
                  case SnapshotFilter(_, _, filter) => filter
                }
              } ~
                delete { implicit ctx =>
                  (cryoctx.inventory ? SnapshotUpdateFilter(snapshotId, filepath, NoOne)) expect {
                    case FilterUpdated() => "OK filter removed"
                  }
                } ~
                post {
                  entity(as[FileFilter]) { filter =>
                    implicit ctx =>
                      (cryoctx.inventory ? SnapshotUpdateFilter(snapshotId, filepath, filter)) expect {
                        case FilterUpdated() => "OK filter updated"
                      }
                  }
                } ~
                put {
                  entity(as[FileFilter]) { filter =>
                    implicit ctx =>
                      (cryoctx.inventory ? SnapshotUpdateFilter(snapshotId, filepath, filter)) expect {
                        case FilterUpdated() => "OK filter updated"
                      }
                  }
                }
            } ~
            path("upload") {
              post {
                implicit ctx =>
                  (cryoctx.inventory ? SnapshotUpload(snapshotId)) expect {
                    case SnapshotUploaded(id) => s"OK snapshot ${id} uploaded"
                  }
              }
            }
        }
    }
  }
}