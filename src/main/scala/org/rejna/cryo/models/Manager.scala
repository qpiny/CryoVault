package org.rejna.cryo.models

import akka.actor.Actor

import com.amazonaws.services.glacier.model.GlacierJobDescription

import org.joda.time.DateTime

object JobStatus {
  def apply(name: String, message: String) = name match {
    case "InProgress" => Some(InProgress(message))
    case "Succeeded" => Some(Succeeded(message))
    case "Failed" => Some(Failed(message))
    case _ => None
  }
}
sealed class JobStatus(message: String)
case class InProgress(message: String) extends JobStatus(message)
case class Succeeded(message: String) extends JobStatus(message)
case class Failed(message: String) extends JobStatus(message)

sealed abstract class Job(
  val id: String,
  val description: String,
  val creationDate: DateTime,
  val status: JobStatus,
  val completedDate: Option[DateTime])

case class InventoryJob(
  id: String,
  description: String,
  creationDate: DateTime,
  status: JobStatus,
  completedDate: Option[DateTime]) extends Job(id, description, creationDate, status, completedDate) {
}

case class ArchiveJob(
  id: String,
  description: String,
  creationDate: DateTime,
  status: JobStatus,
  completedDate: Option[DateTime],
  val archiveId: String) extends Job(id, description, creationDate, status, completedDate)

object Job {
  def getStatus(status: String, message: String) = status match {
    case "InProgress" => InProgress(message)
    case "Succeeded" => Succeeded(message)
    case "Failed" => Failed(message)
  }

  def apply(j: GlacierJobDescription): Job = {
    j.getAction match {
      case "ArchiveRetrieval" =>
        ArchiveJob(
          j.getJobId,
          j.getJobDescription,
          DateUtil.fromISOString(j.getCreationDate),
          getStatus(j.getStatusCode, j.getStatusMessage),
          Option(DateUtil.fromISOString(j.getCompletionDate)),
          j.getArchiveId)
      case "InventoryRetrieval" =>
        InventoryJob(
          j.getJobId,
          j.getJobDescription,
          DateUtil.fromISOString(j.getCreationDate),
          getStatus(j.getStatusCode, j.getStatusMessage),
          Option(DateUtil.fromISOString(j.getCompletionDate)))
    }
  }
}
case class AddJob(job: Job)
case class RemoveJob(jobId: String)
case class JobList(jobs: List[Job])

class Manager extends Actor {
  val attributeBuilder = new AttributeBuilder("/user/manager")
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

    case JobList(jl) =>
      for (job <- jl)
        jobs += job.id -> job
    // TODO remove obsolete jobs
  }
}