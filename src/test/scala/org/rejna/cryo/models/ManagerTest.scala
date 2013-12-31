package org.rejna.cryo.models

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ TestKit, TestActorRef, ImplicitSender }

import com.typesafe.config.ConfigFactory

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.language.postfixOps

import org.scalatest.{ FlatSpecLike, BeforeAndAfter }
import org.scalatest.junit.{ JUnitRunner, AssertionsForJUnit }

import org.junit.runner.RunWith

import java.util.Date

@RunWith(classOf[JUnitRunner])
class ManagerTest extends TestKit(ActorSystem())
  with ImplicitSender
  with FlatSpecLike
  with BeforeAndAfter
  with AssertionsForJUnit {

  val cryoctx = new CryoContext(system, ConfigFactory.load())
  val managerRef = TestActorRef[Manager](Props(classOf[Manager], cryoctx))
  val manager = managerRef.underlyingActor

  val job1 = Job(
    "inventoryJobId",
    "Inventory job",
    new Date,
    InProgress(),
    None,
    "inventory")
  val job2 = Job(
    "archiveJobId",
    "Archive job",
    new Date,
    InProgress(),
    None,
    "archiveId")
  val jobList = job1 :: job2 :: Nil

  before {
    manager.jobs.clear
    manager.finalizedJobs.clear
    manager.jobUpdated = Promise[Unit]()
  }

  "Manager actor" should "accept new jobs" in {
    managerRef ! AddJob(job1)
    expectMsg(JobAdded(job1))
    assert(manager.jobs == jobList.map(j => j.id -> j).toMap)
    assert(manager.jobUpdated.isCompleted == false)
  }

  it should "update an already submited job" in {
    val updatedJob2 = Job(
      "archiveJobId",
      "Description is changed",
      new Date,
      Succeeded(),
      Some(new Date),
      "archiveId")
    manager.jobs ++= jobList.map(j => j.id -> j)
    managerRef ! AddJob(updatedJob2)
    expectMsg(JobAdded(updatedJob2))
    assert(manager.jobs == (job1 :: updatedJob2 :: Nil).map(j => j.id -> j).toMap)
    assert(manager.jobUpdated.isCompleted == false)
  }
  
  it should "ignore jobs that are already finished" in {
    manager.finalizedJobs += "archiveJobId" -> Job("archiveJobId", "description of archiveJob", new Date, Finalized(), None, "ArchiveId")
    managerRef ! AddJob(job2)
    expectMsg(JobAdded(job2))
    assert(manager.jobs == Map.empty[String, Job])
    assert(manager.jobUpdated.isCompleted == false)
  }
  
  it should "remove only present jobs" in {
    manager.jobs ++= jobList.map(j => j.id -> j)
    managerRef ! RemoveJob("nonExistantJob")
    expectMsg(JobRemoved("nonExistantJob"))
    assert(manager.jobs == (job1 :: Nil).map(j => j.id -> j).toMap)
    assert(manager.jobUpdated.isCompleted == false)
  }
  
  it should "update its job list" in {
    managerRef ! UpdateJobList(jobList)
    expectMsg(JobListUpdated(jobList))
    assert(manager.jobs == jobList.map(j => j.id -> j).toMap)
    assert(manager.jobUpdated.isCompleted == true)
  }
  
  it should "wait an updated job list before return its job list" in {
    manager.jobs ++= jobList.map(j => j.id -> j)
    managerRef ! GetJobList()
    assert(manager.jobUpdated.isCompleted == false)
    expectNoMsg(1 second)
    managerRef ! UpdateJobList(Nil)
    expectMsgAllOf(JobListUpdated(Nil), JobList(jobList))
    assert(manager.jobUpdated.isCompleted == true)
  }
  
  it should "finalize jobs" in {
    manager.jobs ++= jobList.map(j => j.id -> j)
    managerRef ! FinalizeJob(job1.id)
    expectMsg(job1.copy(status = Finalized()))
    assert(manager.jobs == Map(job2.id -> job2))
    assert(manager.finalizedJobs == Map(job1.id -> job1.copy(status = Finalized())))
    assert(manager.jobUpdated.isCompleted == false)
  }
}