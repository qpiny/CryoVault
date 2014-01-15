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

import DataStatus._

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
  objectId: String)

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
      case Success(Done()) => log.info("Job list has been refreshed")
      case o: Any => log(cryoError("Fail to refresh job list", o))
    }

    (cryoctx.datastore ? GetDataEntry("finalizedJobs"))
      .eflatMap("Fail to get finalizedJobs", {
        case DataEntry(id, _, _, _, Readable, size, _) if size > 0 =>
          (cryoctx.datastore ? ReadData(id, 0, size.toInt))
      }).emap("Fail to read finalizedJobs", {
        case DataRead(_, _, buffer) =>
          finalizedJobs ++= Json.read[List[Job]](buffer).map(j => j.id -> j)
      }).onComplete {
        case Success(_) => log.info("Finalized jobs loaded")
        case Failure(NotFound(_, _, _)) => log.info("Finalized jobs not found")
        case Failure(e) => log.error("Fail to load Finalized jobs", e)
      }

  }

  def receive = cryoReceive {
    case PrepareToDie() if !isDying =>
      isDying = true

      (cryoctx.datastore ? CreateData(Some(finalizedJobsId), DataType.Internal))
        .eflatMap("Fail to create finalizedJobs data", {
          case Created(id) => cryoctx.datastore ? WriteData(id, ByteString((Json.write(finalizedJobs.values))))
        }).eflatMap("Fail to write finalizedJobs data", {
          case DataWritten(id, _, _) => cryoctx.datastore ? PackData(id, finalizedJobsGlacierId)
        }).emap("Fail to save finalized jobs", {
          case DataPacked(_, _) =>
            log.info("FinalizedJobs data has been stored")
            ReadyToDie()
        }).recover({
          case t: Any =>
            log(cryoError("Fail to save finalized jobs", t))
            ReadyToDie()
        }).reply("Fail to save finalized jobs", sender)

    case AddJob(addedJob) =>
      if (!finalizedJobs.contains(addedJob.id))
        jobs += addedJob.id -> addedJob
      sender ! JobAdded(addedJob)

    case RemoveJob(jobId) =>
      if (jobs.contains(jobId)) {
        jobs -= jobId
        sender ! JobRemoved(jobId)
      } else {
        sender ! NotFoundError(s"Can't remove a non-existent job ${jobId}")
      }

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
            case None => sender ! NotFoundError(s"Job ${jobId} is not found and can't be finalized")
          }
      }

    case GetJob(jobId) =>
      jobs.get(jobId).orElse(finalizedJobs.get(jobId)) match {
        case Some(j) => sender ! j
        case None => sender ! NotFoundError(s"Job ${jobId} is not found")
      }

  }
}