package org.rejna.cryo.web

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Success, Failure, Try }
import scala.reflect.ClassTag

import spray.http.MediaTypes.{ `text/html` }
import spray.http.StatusCodes._
import spray.routing.{ HttpService, Route, ExceptionHandler, PathMatchers, RequestContext }
import spray.httpx.Json4sSupport
import spray.httpx.marshalling.ToResponseMarshaller

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

  val staticContent = path(PathMatchers.PathEnd) {
    getFromResource("webapp/index.html")
  } ~
  path("exit") { ctx =>
    cryoctx.shutdown
    ctx.complete("OK")
  } ~ getFromResourceDirectory("webapp")
  def json4sFormats = Json
  def actorRefFactory = context
  def receive = cryoReceive(runRoute(routes ~ staticContent))
}

trait ComposableRoute extends HttpService {
  private var routeList: List[Route] = Nil
  def addRoute(route: Route) = routeList = route :: routeList
  def routes = routeList reduce { _ ~ _ }
}

trait CryoExpectableSupport { self: HttpService =>
  implicit val executionContext: ExecutionContext
  // TODO needs improvement
  class ExpectableFuture(f: Future[_])(implicit ctx: RequestContext) {
    def expect[T](pf: PartialFunction[Any, T])(implicit ee: ToResponseMarshaller[T]): Unit = {
      f onComplete {
        case Success(m) if pf.isDefinedAt(m) => ctx.complete(pf(m))
        case Success(m) => ctx.withHttpResponseMapped(_.copy(status = InternalServerError)).complete(s"Unexpexted message : ${m}")
        case Failure(t) => ctx.withHttpResponseMapped(_.copy(status = InternalServerError)).complete("Error")
      }
    }

    def expect[T](implicit ee: ToResponseMarshaller[T], tag: ClassTag[T]): Unit = {
      val expectedClass = tag.runtimeClass
      val a = f.map(m => Try(ctx.complete(expectedClass.cast(m).asInstanceOf[T]))
        .recover { case _ => ctx.withHttpResponseMapped(_.copy(status = InternalServerError)).complete(s"Unpexpected message : ${m}") })
        .recover { case t => ctx.withHttpResponseMapped(_.copy(status = InternalServerError)).complete(s"Internal error : ${t}") }

    }
  }
  implicit def aa(f: Future[_])(implicit ctx: RequestContext) = new ExpectableFuture(f)
}