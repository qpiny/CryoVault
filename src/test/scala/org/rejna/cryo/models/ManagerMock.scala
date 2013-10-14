package org.rejna.cryo.models

import akka.actor.{ Actor, Stash }

import java.util.Date

class ManagerMock(val cryoctx: CryoContext) extends Actor with Stash {
  
  def receive = {
    case MakeActorReady =>
      unstashAll()
      context.become(receiveWhenReady)
    case _ =>
      stash()
  }
  
  def receiveWhenReady: Receive = {
    case AddJobs(jobs) =>
      sender ! JobsAdded(jobs)
    case RemoveJobs(jobIds) =>
      sender ! JobsRemoved(jobIds)
    case UpdateJobList(jobs) =>
      sender ! JobListUpdated(jobs)
    case GetJobList() =>
      sender ! JobList(List.empty[Job])
    case FinalizeJob(jobId: String) =>
      sender ! Job(jobId, "", new Date, Finalized("Finalized"), None, "inventory")

  }
}