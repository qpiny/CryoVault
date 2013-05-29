package org.rejna.cryo.models

import scala.concurrent.Promise
import scala.collection.mutable.HashSet

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
sealed class JobStatus(message: String) {
  val isInProgress = false
  val isSucceeded = false
  val isFailed = false
}
case class InProgress(message: String) extends JobStatus(message) { override val isInProgress = true}
case class Succeeded(message: String) extends JobStatus(message) { override val isSucceeded = true}
case class Failed(message: String) extends JobStatus(message) { override val isFailed = true}

sealed abstract class Job {
  val id: String
  val description: String
  val creationDate: DateTime
  val status: JobStatus
  val completedDate: Option[DateTime]
}

case class InventoryJob(
  id: String,
  description: String,
  creationDate: DateTime,
  status: JobStatus,
  completedDate: Option[DateTime]) extends Job

case class ArchiveJob(
  id: String,
  description: String,
  creationDate: DateTime,
  status: JobStatus,
  completedDate: Option[DateTime],
  val archiveId: String) extends Job

object Job {
  def getStatus(status: String, message: String) = status match {
    case "InProgress" => InProgress(message)
    case "Succeeded" => Succeeded(message)
    case "Failed" => Failed(message)
  }

  def apply(j: GlacierJobDescription): Job = {
    j.getAction match {
      case "ArchiveRetrieval" =>
        new ArchiveJob(
          j.getJobId,
          j.getJobDescription,
          DateUtil.fromISOString(j.getCreationDate),
          getStatus(j.getStatusCode, j.getStatusMessage),
          Option(DateUtil.fromISOString(j.getCompletionDate)),
          j.getArchiveId)
      case "InventoryRetrieval" =>
        new InventoryJob(
          j.getJobId,
          j.getJobDescription,
          DateUtil.fromISOString(j.getCreationDate),
          getStatus(j.getStatusCode, j.getStatusMessage),
          Option(j.getCompletionDate).map(DateUtil.fromISOString))
    }
  }
}

sealed abstract class ManagerRequest extends Request
sealed abstract class ManagerResponse extends Response
sealed class ManagerError(message: String, cause: Throwable) extends CryoError(message, cause)

case class AddJobs(jobs: List[Job]) extends ManagerRequest
object AddJobs { def apply(jobs: Job*): AddJobs = AddJobs(jobs.toList) }
case class JobsAdded(jobs: List[Job]) extends ManagerResponse
case class RemoveJobs(jobIds: List[String]) extends ManagerRequest
object RemoveJobs { def apply(jobIds: String*): RemoveJobs = RemoveJobs(jobIds.toList) }
case class JobsRemoved(jobIds: List[String]) extends ManagerResponse
case class UpdateJobList(jobs: List[Job]) extends ManagerRequest
case class JobListUpdated(jobs: List[Job]) extends ManagerResponse
case class GetJobList() extends ManagerRequest
case class JobList(jobs: List[Job]) extends ManagerResponse
case class FinalizeJob(jobIds: List[String]) extends ManagerRequest
object FinalizeJob { def apply(jobIds: String*): FinalizeJob = FinalizeJob(jobIds.toList) }
case class JobFinalized(jobIds: List[String]) extends ManagerResponse

class Manager(cryoctx: CryoContext) extends Actor with LoggingClass {
  val attributeBuilder = AttributeBuilder("/cryo/manager")
  val jobs = attributeBuilder.map("jobs", Map[String, Job]())
  val finalizedJobs = HashSet.empty[String]
  val jobUpdated = Promise[Unit]()

  override def preStart = {
    cryoctx.cryo ! RefreshJobList()
  }

  def receive = CryoReceive {
    case AddJobs(addedJobs) =>
      jobs ++= addedJobs.filterNot(j => finalizedJobs.contains(j.id)).map(j => j.id -> j)
      sender ! JobsAdded(addedJobs)

    case RemoveJobs(jobIds) =>
      jobs --= jobIds
      sender ! JobsRemoved(jobIds)

    case UpdateJobList(jl) =>
      jobs ++= jl.filterNot(j => finalizedJobs.contains(j.id)).map(j => j.id -> j)
      // TODO remove obsolete jobs ?
      if (!jobUpdated.isCompleted)
        jobUpdated.success()
      sender ! JobListUpdated(jl)

    case GetJobList() =>
      if (jobUpdated.isCompleted) {
    	  sender ! JobList(jobs.values.toList)
      } else {
        implicit val executionContext = context.system.dispatcher
        val requester = sender
        jobUpdated.future.onSuccess { case _ => requester ! JobList(jobs.values.toList) }
      }
      
    case FinalizeJob(jobIds) =>
      jobs --= jobIds
      finalizedJobs ++= jobIds
      sender ! JobFinalized(jobIds)
  }
}