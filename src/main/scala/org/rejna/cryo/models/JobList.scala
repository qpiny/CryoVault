package org.rejna.cryo.models

import akka.actor.Actor

import com.amazonaws.services.glacier.transfer.ArchiveTransferManager

case class AddJob(job: Job)
case class RemoveJob(jobId: String)
case class NewJobList(jobs: List[Job])

class JobList(attributeBuilder: AttributeBuilder) extends Actor {
  val jobs = attributeBuilder.map("jobs", Map[String, Job]())
  val cryo = context.actorFor("/user/cryo")

  def preStart = {
    cryo ! RefreshJobList
  }
  
  def receive = {
    case AddJob(job) =>
      jobs += job.id -> job

    case RemoveJob(jobId) =>
      jobs -= jobId

    case NewJobList(jl) =>
      for (job <- jl)
        jobs.get(job.id) match {
          case Some(j) =>
            jobs += job.id -> j.update(job)
          case None =>
            jobs += job.id -> job
        }
    // TODO remove jobs
  }
}