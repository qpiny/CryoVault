package org.rejna.cryo.models

import akka.actor.Actor

class ManagerMock(val cryoctx: CryoContext) extends Actor {

  def receive = {
    case AddJobs(jobs) =>
      sender ! JobsAdded(jobs)
    case RemoveJobs(jobIds) =>
      sender ! JobsRemoved(jobIds)
    case UpdateJobList(jobs) =>
      sender ! JobListUpdated(jobs)
    case GetJobList() =>
      sender ! JobList(List.empty[Job])
    case FinalizeJob(jobIds: List[String]) =>
      sender ! JobFinalized(jobIds)

  }
}