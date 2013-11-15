package org.rejna.cryo.web

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Success, Failure, Try }
import scala.reflect.ClassTag

import spray.http.MediaTypes.{ `text/html` }
import spray.http.StatusCodes._
import spray.routing.{ HttpService, Route, ExceptionHandler, PathMatchers }
import spray.httpx.Json4sSupport
import spray.httpx.marshalling.ToResponseMarshallable

import org.rejna.cryo.models._

class WebServiceActor(_cryoctx: CryoContext)
  extends CryoActor(_cryoctx)
  with SnapshotService {

  implicit def myExceptionHandler = ExceptionHandler {
    case e: ArithmeticException => ctx =>
      ctx.withHttpResponseMapped(_.copy(status = InternalServerError))
        .complete("Bad numbers, bad result!!!")
  }

  def actorRefFactory = context
  def receive = cryoReceive(runRoute(routes))
}

trait ComposableRoute extends HttpService {
  private var routeList: List[Route] = Nil
  def addRoute(route: Route) = routeList = route :: routeList
  def routes = routeList reduce { _ ~ _ }
}

trait CryoExpectableSupport { self: HttpService =>
  implicit val executionContext: ExecutionContext
// TODO needs improvement
  class ExpectableFuture(f: Future[_]) {
    def expect[T](pf: PartialFunction[Any, T])(implicit ee: T => ToResponseMarshallable): Unit = {
      f onComplete {
        case Success(m) if pf.isDefinedAt(m) => complete(pf(m))
        case Success(m) => respondWithStatus(InternalServerError) { complete(s"Unexpexted message : ${m}") }
        case Failure(t) => respondWithStatus(InternalServerError) { complete("Error") }
      }
    }

    def expect[T](implicit ee: T => ToResponseMarshallable, tag: ClassTag[T]): Unit = {
      val expectedClass = tag.runtimeClass
      val a = f.map(m => Try(complete(expectedClass.cast(m).asInstanceOf[T]))
        .recover { case _ => complete(s"Unpexpected message : ${m}") })
        .recover { case t => complete(s"Internal error : ${t}") }
      
    }
  }
 implicit def aa(f: Future[_]) = new ExpectableFuture(f)
}

trait SnapshotService
  extends ComposableRoute
  with CryoAskSupport
  with Json4sSupport
  with CryoExpectableSupport {

  implicit val cryoctx: CryoContext
  implicit val executionContext: ExecutionContext

  val FilePath = Segment.map(_.replace('!', '/'))
  
  def unexpected(m: Any)
  addRoute {
    pathPrefix("snapshots") {
      path(PathMatchers.PathEnd) { post { ctx =>
        (cryoctx.inventory ? CreateSnapshot()) expect {
          case SnapshotCreated(snapshotId) => snapshotId
        }
      } } ~
      path("list") { get { ctx =>
        (cryoctx.inventory ? GetSnapshotList()) expect {
          case sl: SnapshotList => sl.snapshots
        }
      } } ~
      path(Segment) { snapshotId =>
        get { ctx =>
          (cryoctx.datastore ? GetDataStatus(snapshotId)).expect[DataStatus]
        } ~
        delete { ctx =>
          (cryoctx.inventory ? DeleteSnapshot(snapshotId)) expect {
            case sd: SnapshotDeleted => "OK"
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
              case FilterUpdated() => "OK"
            }
          } ~
          post {
            entity(as[FileFilter]) { filter => ctx =>
              (cryoctx.inventory ? SnapshotUpdateFilter(snapshotId, filepath, filter)) expect {
                case FilterUpdated() => "OK"
              }
            }
          } ~
          put {
            entity(as[FileFilter]) { filter => ctx =>
              (cryoctx.inventory ? SnapshotUpdateFilter(snapshotId, filepath, filter)) expect {
                case FilterUpdated() => "OK"
              }
            }
          }
        }
      }

    }
  }

  addRoute {
    //path("snapshots") {
    get {
      pathPrefix("snapshots/list") {
        complete {
          <html></html>
        }
      } ~
        path("snapshots" / Segment) { id =>
          respondWithMediaType(`text/html`) { // XML is marshalled to `text/xml` by default, so we simply override here
            complete {
              <html>
                <body>
                  <h1>The <b>S4</b> - <i>Slick Spray Scala Stack</i> is running :-)</h1>
                </body>
              </html>
            }
          }
        }
    }
  }

}