package org.rejna.cryo.models

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ TestKit, TestActorRef, ImplicitSender }

import com.typesafe.config.ConfigFactory

import scala.concurrent.Promise
import scala.concurrent.duration._

import org.scalatest.matchers.MustMatchers
import org.scalatest.{ FlatSpecLike, BeforeAndAfter }

import java.util.Date

class ManagerTest extends TestKit(ActorSystem())
  with ImplicitSender
  with FlatSpecLike
  with BeforeAndAfter {

  val cryoctx = new CryoContext(system, ConfigFactory.load())
  val managerRef = TestActorRef[Manager](Props(classOf[Manager], cryoctx))
  val manager = managerRef.underlyingActor

  val job1 = InventoryJob(
    "inventoryJobId",
    "Inventory job",
    new Date,
    InProgress("In progress"),
    None)
  val job2 = ArchiveJob(
    "archiveJobId",
    "Archive job",
    new Date,
    InProgress("In progress"),
    None,
    "archiveId")
  val jobList = job1 :: job2 :: Nil

  before {
    manager.jobs.clear
    manager.finalizedJobs.clear
    manager.jobUpdated = Promise[Unit]()
  }

  "Manager actor" must "accept new jobs" in {
    managerRef ! AddJobs(jobList)
    expectMsg(JobsAdded(jobList))
    assert(manager.jobs == jobList.map(j => j.id -> j).toMap)
    assert(manager.jobUpdated.isCompleted == false)
  }

  it must "update an already submited job" in {
    val updatedJob2 = ArchiveJob(
      "archiveJobId",
      "Description is changed",
      new Date,
      Succeeded("job is ok"),
      Some(new Date),
      "archiveId")
    manager.jobs ++= jobList.map(j => j.id -> j)
    managerRef ! AddJobs(updatedJob2)
    expectMsg(JobsAdded(updatedJob2 :: Nil))
    assert(manager.jobs == (job1 :: updatedJob2 :: Nil).map(j => j.id -> j).toMap)
    assert(manager.jobUpdated.isCompleted == false)
  }
  
  it must "ignore jobs that are already finished" in {
    manager.finalizedJobs += "archiveJobId"
    managerRef ! AddJobs(job2)
    expectMsg(JobsAdded(Nil))
    assert(manager.jobs == Map.empty[String, Job])
    assert(manager.jobUpdated.isCompleted == false)
  }
  
  it must "remove only present jobs" in {
    manager.jobs ++= jobList.map(j => j.id -> j)
    managerRef ! RemoveJobs("nonExistantJob", "archiveJobId", "otherJobId")
    expectMsg(JobsRemoved("archiveJobId" :: Nil))
    assert(manager.jobs == (job1 :: Nil).map(j => j.id -> j).toMap)
    assert(manager.jobUpdated.isCompleted == false)
  }
  
  it must "update its job list" in {
    managerRef ! UpdateJobList(jobList)
    expectMsg(JobListUpdated(jobList))
    assert(manager.jobs == jobList.map(j => j.id -> j).toMap)
    assert(manager.jobUpdated.isCompleted == true)
  }
  
  it must "wait an updated job list before return its job list" in {
    manager.jobs ++= jobList.map(j => j.id -> j)
    managerRef ! GetJobList()
    assert(manager.jobUpdated.isCompleted == false)
    expectNoMsg(1 second)
    managerRef ! UpdateJobList(Nil)
    expectMsgAllOf(JobListUpdated(Nil), JobList(jobList))
    assert(manager.jobUpdated.isCompleted == true)
  }
  
  it must "finalize jobs" in {
    manager.jobs ++= jobList.map(j => j.id -> j)
    managerRef ! FinalizeJob("archiveJobId", "otherId")
    expectMsg(JobFinalized("archiveJobId" :: "otherId" :: Nil))
    assert(manager.jobs == Map(job1.id -> job1))
    assert(manager.finalizedJobs == Set("archiveJobId", "otherId"))
    assert(manager.jobUpdated.isCompleted == false)
  }
}