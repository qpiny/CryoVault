package org.rejna.cryo.web

import spray.http.MediaTypes.{ `text/html` }
import spray.routing.{ HttpService, Route }

import org.rejna.cryo.models.{ CryoActor, CryoContext }

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

trait SnapshotService extends ComposableRoute {
  addRoute {
    pathPrefix("snapshots") {
      path("list") {}
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