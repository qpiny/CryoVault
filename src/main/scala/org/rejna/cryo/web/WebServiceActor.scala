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
  with SnapshotService
  with ArchiveService
  with JobService {

  implicit def myExceptionHandler = ExceptionHandler {
    case e: ArithmeticException => ctx =>
      ctx.withHttpResponseMapped(_.copy(status = InternalServerError))
        .complete("Bad numbers, bad result!!!")
  }

  def json4sFormats = Json
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