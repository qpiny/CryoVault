package org.rejna.cryo.models

import scala.concurrent.Promise
import scala.collection.mutable.HashSet
import scala.language.postfixOps
import scala.util.{ Success, Failure }

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption._
import java.nio.ByteBuffer
import java.util.{ Date, UUID }

import akka.util.ByteString
import akka.event.Logging.Error

import com.amazonaws.services.glacier.model.GlacierJobDescription

import ObjectStatus._

object JobStatus {
  def apply(name: String, message: String) = name match {
    case "InProgress" => Some(InProgress(message))
    case "Succeeded" => Some(Succeeded(message))
    case "Failed" => Some(Failed(message))
    case "Finalized" => Some(Finalized(message))
    case _ => None
  }
}
sealed class JobStatus(message: String) {
  val isInProgress = false
  val isSucceeded = false
  val isFailed = false
  val isFinalized = false
}
case class InProgress(message: String = "In progress") extends JobStatus(message) { override val isInProgress = true }
case class Succeeded(message: String = "Succeeded") extends JobStatus(message) { override val isSucceeded = true }
case class Failed(message: String = "Failed") extends JobStatus(message) { override val isFailed = true }
case class Finalized(message: String = "Finalized") extends JobStatus(message) { override val isFinalized = true }

case class Job(id: String,
  description: String,
  creationDate: Date,
  status: JobStatus,
  completedDate: Option[Date],
  objectId: String) extends ManagerResponse

object Job {
  def getStatus(status: String, message: String) = status match {
    case "InProgress" => InProgress(message)
    case "Succeeded" => Succeeded(message)
    case "Failed" => Failed(message)
    case "Finalized" => Finalized(message)
  }

  def apply(j: GlacierJobDescription): Job = {
    // TODO put in job serialization (in Json.scala)
    j.getAction match {
      case "ArchiveRetrieval" =>
        new Job(
          j.getJobId,
          Option(j.getJobDescription).getOrElse(""),
          Json.readDate(j.getCreationDate).getOrElse(new Date()), //DateUtil.fromISOString(j.getCreationDate), // FIXME
          getStatus(j.getStatusCode, j.getStatusMessage),
          Json.readDate(j.getCompletionDate()),
          j.getArchiveId)
      case "InventoryRetrieval" =>
        new Job(
          j.getJobId,
          Option(j.getJobDescription).getOrElse(""),
          Json.readDate(j.getCreationDate).getOrElse(new Date()),
          getStatus(j.getStatusCode, j.getStatusMessage),
          Json.readDate(j.getCompletionDate()), //Option(j.getCompletionDate).map(DateUtil.fromISOString))
          "inventory")
    }
  }
}

sealed abstract class ManagerRequest extends Request
sealed abstract class ManagerResponse extends Response
sealed abstract class ManagerError(message: String, cause: Throwable) extends GenericError {
  val source = classOf[Manager].getName
  val marker = Markers.errMsgMarker
}

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
case class GetJob(jobId: String) extends ManagerRequest
case class JobNotFound(jobId: String, message: String, cause: Throwable = Error.NoCause) extends ManagerError(message, cause)
case class FinalizeJob(jobId: String) extends ManagerRequest

class Manager(_cryoctx: CryoContext) extends CryoActor(_cryoctx) {
  val attributeBuilder = CryoAttributeBuilder("/cryo/manager")
  val jobs = attributeBuilder.map("jobs", Map[String, Job]())
  val finalizedJobs = attributeBuilder.map("finalizedJobs", Map[String, Job]())
  var jobUpdated = Promise[Unit]() /* var instead of val for test */
  var isDying = false

  val finalizedJobsId = new UUID(0x0000000000001000L, 0xC47000000002L)
  val finalizedJobsGlacierId = "finalizedJobs"
    
  override def preStart = {
    log.info("Starting manager ...")
    log.info("Refreshing job list")
    (cryoctx.cryo ? RefreshJobList()) onComplete {
      case Success(JobListRefreshed()) => log.info("Job list has been refreshed")
      case o: Any => log(CryoError("Fail to refresh job list", o))
    }

    (cryoctx.datastore ? GetDataStatus("finalizedJobs")) flatMap {
      case DataStatus(id, _, _, status, size, _) if status == Cached && size > 0 =>
        (cryoctx.datastore ? ReadData(id, 0, size.toInt))
      case dnfe: DataNotFoundError => throw dnfe
      case o: Any =>
        throw CryoError("Fail to get finalizedJobs", o)
    } map {
      case DataRead(_, _, buffer) =>
        finalizedJobs ++= Json.read[List[Job]](buffer).map(j => j.id -> j)
      case o: Any =>
        throw CryoError("Fail to read finalizedJobs", o)
    } onComplete {
      case Success(_) => log.info("Finalized jobs loaded")
      case Failure(DataNotFoundError(_, _, _)) => log.info("Finalized jobs not found")
      case Failure(e) => log.error("Fail to load Finalized jobs", e)
    }

  }

  def receive = cryoReceive {
    case PrepareToDie() if !isDying =>
      isDying = true

      val _sender = sender
      (cryoctx.datastore ? CreateData(Some(finalizedJobsId), DataType.Internal)) eflatMap("") {
        case DataCreated(id) => cryoctx.datastore ? WriteData(id, ByteString((Json.write(finalizedJobs.values))))
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
          log(CryoError("Fail to save finalized jobs", o))
      }

    case AddJobs(addedJobs) =>
      val unfinalizedJobs = addedJobs.filterNot(j => finalizedJobs.contains(j.id))
      jobs ++= unfinalizedJobs.map(j => j.id -> j)
      sender ! JobsAdded(unfinalizedJobs)

    case RemoveJobs(jobIds) =>
      val presentJobs = jobIds.filter(jobs.contains(_))
      jobs --= presentJobs
      sender ! JobsRemoved(presentJobs)

    case UpdateJobList(jl) =>
      val unfinalizedJobs = jl.filterNot(j => finalizedJobs.contains(j.id))
      jobs ++= unfinalizedJobs.map(j => j.id -> j)
      // TODO remove obsolete jobs ?
      if (!jobUpdated.isCompleted)
        jobUpdated.success()
      sender ! JobListUpdated(unfinalizedJobs)

    case GetJobList() =>
      log.debug("Receive GetJobList()")
      if (jobUpdated.isCompleted) {
        log.debug("Reply to GetJobList()")
        sender ! JobList(jobs.values.toList)
      } else {
        log.debug("Relay reply to GetJobList()")
        val _sender = sender
        jobUpdated.future.onSuccess { case _ => _sender ! JobList(jobs.values.toList) }
      }

    case FinalizeJob(jobId) =>
      val job = jobs.remove(jobId).map(_.copy(status = Finalized()))

      job match {
        case Some(j) =>
          finalizedJobs += j.id -> j
          sender ! j
        case None =>
          finalizedJobs.get(jobId) match {
            case Some(j) => sender ! j
            case None => sender ! JobNotFound(jobId, s"Job ${jobId} is not found and can't be finalized")
          }
      }

    case GetJob(jobId) =>
      jobs.get(jobId).orElse(finalizedJobs.get(jobId)) match {
        case Some(j) => sender ! j
        case None => sender ! JobNotFound(jobId, s"Job ${jobId} is not found")
      }

  }
}