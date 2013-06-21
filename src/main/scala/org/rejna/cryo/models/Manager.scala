package org.rejna.cryo.models

import scala.concurrent.Promise
import scala.collection.mutable.HashSet
import scala.language.postfixOps
import scala.util.{ Success, Failure }

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption._
import java.nio.ByteBuffer
import java.util.Date

import akka.util.ByteString

import com.amazonaws.services.glacier.model.GlacierJobDescription
import resource.managed
import net.liftweb.json.Serialization

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
case class InProgress(message: String) extends JobStatus(message) { override val isInProgress = true }
case class Succeeded(message: String) extends JobStatus(message) { override val isSucceeded = true }
case class Failed(message: String) extends JobStatus(message) { override val isFailed = true }

sealed abstract class Job {
  val id: String
  val description: String
  val creationDate: Date
  val status: JobStatus
  val completedDate: Option[Date]
}

case class InventoryJob(
  id: String,
  description: String,
  creationDate: Date,
  status: JobStatus,
  completedDate: Option[Date]) extends Job

case class ArchiveJob(
  id: String,
  description: String,
  creationDate: Date,
  status: JobStatus,
  completedDate: Option[Date],
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
          Option(j.getJobDescription).getOrElse(""),
          Json.dateFormat.parse(j.getCreationDate).getOrElse(new Date()),//DateUtil.fromISOString(j.getCreationDate), // FIXME
          getStatus(j.getStatusCode, j.getStatusMessage),
          Json.dateFormat.parse(j.getCompletionDate()),
          j.getArchiveId)
      case "InventoryRetrieval" =>
        new InventoryJob(
          j.getJobId,
          Option(j.getJobDescription).getOrElse(""),
          Json.dateFormat.parse(j.getCreationDate).getOrElse(new Date()),
          getStatus(j.getStatusCode, j.getStatusMessage),
          Json.dateFormat.parse(j.getCompletionDate())) //Option(j.getCompletionDate).map(DateUtil.fromISOString))
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

class Manager(val cryoctx: CryoContext) extends CryoActor {
  val attributeBuilder = CryoAttributeBuilder("/cryo/manager")
  val jobs = attributeBuilder.map("jobs", Map[String, Job]())
  val finalizedJobs = HashSet.empty[String]
  val jobUpdated = Promise[Unit]()

  override def preStart = {
    implicit val formats = Json
    log.info("Starting manager ...")
    log.info("Refreshing job list")
    (cryoctx.cryo ? RefreshJobList()) onComplete {
      case Success(JobListRefreshed()) => log.info("Job list has been refreshed")
      case o: Any => log.error(CryoError("Fail to refresh job list", o))
    }

    (cryoctx.datastore ? GetDataStatus("finalizedJobs")) flatMap {
      case DataStatus(id, _, _, status, size, _) if status == EntryStatus.Created && size > 0 =>
        (cryoctx.datastore ? ReadData(id, 0, size.toInt))
      case dnfe: DataNotFoundError => throw dnfe
      case o: Any =>
        throw CryoError("Fail to get finalizedJobs", o)
    } map {
      case DataRead(_, _, buffer) =>
        finalizedJobs ++= Serialization.read[List[String]](buffer.decodeString("UTF-8"))
      case o: Any =>
        throw CryoError("Fail to read finalizedJobs", o)
    } onComplete {
      case Success(_) => log.info("Finalized jobs loaded")
      case Failure(DataNotFoundError(_, _, _)) => log.info("Finalized jobs not found")
      case Failure(e) => log.error("Fail to load Finalized jobs", e)
    }

  }

  def receive = cryoReceive {
    case PrepareToDie() =>
      val _sender = sender
      implicit val formats = Json
      cryoctx.datastore ? CreateData(Some("finalizedJobs"), "Finalized jobs") flatMap {
        case DataCreated(id) => cryoctx.datastore ? WriteData(id, ByteString((Serialization.write(finalizedJobs))))
        case o: Any => throw CryoError("Fail to create finalizedJobs data", o)
      } flatMap {
        case DataWritten(id, _, _) => cryoctx.datastore ? CloseData(id)
        case o: Any => throw CryoError("Fail to write finalizedJobs data", o)
      } onComplete {
        case Success(DataClosed(_)) =>
          _sender ! ReadyToDie()
          log.info("FinalizedJobs data has been stored")
        case o: Any =>
          _sender ! ReadyToDie()
          log.error("Fail to save finalized jobs", o)
      }
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
        val _sender = sender
        jobUpdated.future.onSuccess { case _ => _sender ! JobList(jobs.values.toList) }
      }

    case FinalizeJob(jobIds) =>
      jobs --= jobIds
      finalizedJobs ++= jobIds
      sender ! JobFinalized(jobIds)
  }
}