package org.rejna.cryo.web

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Success, Failure }

import spray.http.MediaTypes.{ `text/html` }
import spray.routing.{ HttpService, Route }
import spray.httpx.Json4sSupport
import spray.httpx.marshalling.ToResponseMarshallable


import org.rejna.cryo.models._

class WebServiceActor(_cryoctx: CryoContext)
  extends CryoActor(_cryoctx)
  with SnapshotService {

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
  
  class ExpectableFuture(f: Future[_]) {
    def expect[T](pf: PartialFunction[Any, T])(implicit ee: T => ToResponseMarshallable) = {
      f onComplete {
        case Success(m) if pf.isDefinedAt(m) => complete(pf(m))
        case Success(m) => complete(s"Unexpexted message : ${m}")
        case Failure(t) => complete("Error")
      }
    }
//      case x => pf.applyOrElse(x,{
//        case m => complete(s"Unexpected message $")))
//      }
//      case t: T => //complete(t)
//      case e: Any => 
//    }
  }
  //implicit def expect[T](f: Future[_]) = 
}

trait SnapshotService
  extends ComposableRoute
  with CryoAskSupport
  with Json4sSupport {

  implicit val cryoctx: CryoContext
  implicit val executionContext: ExecutionContext

  def unexpected(m: Any)
  addRoute {
    pathPrefix("snapshots") {
      path("list") { ctx =>
        (cryoctx.inventory ? GetSnapshotList()) onComplete {
          case Success(sl: SnapshotList) => complete(sl.snapshots)
        }
        //          case Success(o) => _sender ! GetSnapshotListResponse(ctx.responseContext(501, Map("message" -> o.toString)), List.empty[DataStatus])
        //          case Failure(e) => _sender ! GetSnapshotListResponse(ctx.responseContext(500, Map("message" -> e.getMessage)), List.empty[DataStatus])
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
  
  def doCreate(json: Any) = {
    //We use the Ask pattern to return
    //a future from our worker Actor,
    //which then gets passed to the complete
    //directive to finish the request.
 
    val response = (cryoctx.inventory ? json)
                  .mapTo[String]
                  .map(result => s"I got a response: ${result}")
                  .recover { case _ => "error" }

    complete(response)
  }
}