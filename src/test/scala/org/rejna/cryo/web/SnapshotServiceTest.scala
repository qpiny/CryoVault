package org.rejna.cryo.web

import scala.language.postfixOps
import scala.concurrent.Await
import scala.concurrent.duration._

import spray.http.StatusCodes.Accepted
import spray.testkit.ScalatestRouteTest

import org.scalatest.FlatSpecLike
import org.scalatest.junit.{ JUnitRunner, AssertionsForJUnit }
import org.junit.runner.RunWith

import com.typesafe.config.ConfigFactory

import org.rejna.cryo.models._

@RunWith(classOf[JUnitRunner])
class SnapshotServiceTest
  extends SnapshotService
  with FlatSpecLike
  with ScalatestRouteTest
  with AssertionsForJUnit {

  val actorRefFactory = system
  implicit val cryoctx = new CryoContext(actorRefFactory, ConfigFactory.load())
  implicit val executionContext = actorRefFactory.dispatcher
  def json4sFormats = Json
  def receive = PartialFunction.empty

  val snapshotId = Await.result((cryoctx.inventory ? CreateSnapshot()) map {
    case SnapshotCreated(id) => id
  }, 20 seconds)

  "Snapshot service" must "update file filter" in {
    Post(s"/api/snapshots/${snapshotId}/filter/folder1", FileFilter("ext(txt)")) ~>
      routes ~> check {
        status === Accepted
      }
  }
}