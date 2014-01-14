package org.rejna.cryo.web

import scala.concurrent.ExecutionContext

import java.util.UUID

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
  val uuid = Segment.map(UUID.fromString(_))
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
            case Created(snapshotId) => snapshotId
          }
        }
      } ~
        path("list") {
          get { implicit ctx =>
            (cryoctx.inventory ? GetSnapshotList()) expect {
              case ObjectList(_, snapshots) => snapshots
            }
          }
        } ~
        path(uuid) { snapshotId =>
          get { implicit ctx =>
            (cryoctx.datastore ? GetDataEntry(snapshotId)).expect[DataEntry]
          } ~
            delete { implicit ctx =>
              (cryoctx.inventory ? DeleteSnapshot(snapshotId)) expect {
                case Deleted(id) => s"OK snapshot ${id} deleted"
              }
            }
        } ~
        pathPrefix(uuid) { snapshotId =>
          pathPrefix("files") {
            get { implicit ctx =>
              val path = ctx.unmatchedPath.toString.dropWhile(_ == '/').replace("!", "/")
              (cryoctx.inventory ? GetFileList(snapshotId, path)) expect {
                case FileList(_, _, fe) => fe
              }
            }
          } ~
            path("filter" / filePath) { filepath =>
              get { implicit ctx =>
                (cryoctx.inventory ? GetFilter(snapshotId, filepath)) expect {
                  case SnapshotFilter(_, _, filter) => filter
                }
              } ~
                delete { implicit ctx =>
                  (cryoctx.inventory ? UpdateFilter(snapshotId, filepath, NoOne)) expect {
                    case Done() => "OK filter removed"
                  }
                } ~
                post {
                  entity(as[FileFilter]) { filter =>
                    implicit ctx =>
                      (cryoctx.inventory ? UpdateFilter(snapshotId, filepath, filter)) expect {
                        case Done() => "OK filter updated"
                      }
                  }
                } ~
                put {
                  entity(as[FileFilter]) { filter =>
                    implicit ctx =>
                      (cryoctx.inventory ? UpdateFilter(snapshotId, filepath, filter)) expect {
                        case Done() => "OK filter updated"
                      }
                  }
                }
            } ~
            path("upload") {
              post {
                implicit ctx =>
                  (cryoctx.inventory ? Upload(snapshotId, null)) expect {
                    case Uploaded(id) => s"OK snapshot ${id} uploaded"
                  }
              }
            }
        }
    }
  }
}