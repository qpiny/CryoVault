package org.rejna.cryo.models

import akka.actor.{ Actor, Stash }

import java.util.Date

class ManagerMock(_cryoctx: CryoContext) extends CryoActor(_cryoctx) {
  
  def receive = {
    case MakeActorReady =>
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