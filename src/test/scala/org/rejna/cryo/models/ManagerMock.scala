package org.rejna.cryo.models

import akka.actor.{ Actor, Stash }

import java.util.Date

class ManagerMock(_cryoctx: CryoContext) extends CryoActor(_cryoctx) {
  
  def receive = {
    case MakeActorReady =>
    case AddJob(job) =>
      sender ! JobAdded(job)
    case RemoveJob(jobId) =>
      sender ! JobRemoved(jobId)
    case UpdateJobList(jobs) =>
      sender ! JobListUpdated(jobs)
    case GetJobList() =>
      sender ! JobList(List.empty[Job])
    case FinalizeJob(jobId: String) =>
      sender ! Job(jobId, "", new Date, Finalized("Finalized"), None, "inventory")

  }
}