package org.rejna.cryo.models

import scala.collection.JavaConversions.asScalaBuffer
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.language.postfixOps
import scala.concurrent.Await
import scala.util.{ Failure, Success }

import java.nio.channels.Channels
import java.nio.ByteBuffer
import java.util.Date

import akka.actor.{ Actor, Props }
import akka.util.ByteString

import com.amazonaws.services.glacier.AmazonGlacierClient
import com.amazonaws.services.glacier.model._
import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sns.AmazonSNSClient

import EntryStatus._

class CryoRequest extends Request
class CryoResponse extends Response

case class RefreshJobList() extends CryoRequest
case class JobListRefreshed() extends CryoResponse
case class RefreshInventory() extends CryoRequest
case class RefreshInventoryRequested(job: Job) extends CryoResponse
case class DownloadArchive(archiveId: String) extends CryoRequest
case class DownloadArchiveRequested(job: Job) extends CryoResponse
case class UploadData(id: String) extends CryoRequest
case class DataUploaded(id: String) extends CryoResponse

class Glacier(_cryoctx: CryoContext) extends CryoActor(_cryoctx) {

  val glacier = new AmazonGlacierClient(cryoctx.awsCredentials, cryoctx.awsConfig)
  glacier.setEndpoint("glacier." + cryoctx.region + ".amazonaws.com/")
  if (cryoctx.config.getBoolean("cryo.add-proxy-auth-pref")) HttpClientProxyHack(glacier)
  val futureSnsTopicARN = (cryoctx.notification ? GetNotificationARN()) map {
    case NotificationARN(arn) => arn
    case e: Any => throw CryoError("Fail to get notification ARN", e)
  }
  //lazy val snsTopicARN = Await.result(futureSnsTopicARN, 10 seconds)

  CryoEventBus.subscribe(self, "/cryo/manager#jobs")

  def receive = cryoReceive {
    case PrepareToDie() =>
      sender ! ReadyToDie()

    case AttributeListChange("/cryo/manager#jobs", addedJobs, removedJobs) =>
      val succeededJobs = addedJobs
        .asInstanceOf[List[(String, Job)]]
        .filter { case (_, j) => j.status.isSucceeded }
        .toMap

      succeededJobs.map {
        case (dataId, job) =>
          log.info(s"Job ${job.id} is completed, downloading data ${dataId}")
          var transfer = (cryoctx.datastore ? GetDataStatus(dataId)) map {
            case DataStatus(_, _, _, status, _, _) if status != Creating => // TODO will be Loading when implemented
            case DataNotFoundError(_, _, _) =>
            case o: Any => throw new CryoError(s"Invalid data status ${o}")
          } flatMap {
            Unit => (cryoctx.datastore ? CreateData(Some(dataId), job.description))
          } map {
            case DataCreated(dataId) => log.debug(s"Data ${dataId} created, starting download")
            case o: Any => throw CryoError(s"Fail to create data ${dataId}", o)
          }
          try {
            val input = glacier.getJobOutput(new GetJobOutputRequest()
              .withJobId(job.id)
              .withVaultName(cryoctx.vaultName)).getBody
            try {
              val buffer = Array.ofDim[Byte](cryoctx.bufferSize.intValue)
              Iterator.continually(input.read(buffer))
                .takeWhile(_ != -1)
                .foreach { nRead =>
                  transfer = transfer.flatMap { Unit =>
                    (cryoctx.datastore ? WriteData(dataId, -1, ByteString.fromArray(buffer, 0, nRead))) map {
                      case DataWritten(_, _, s) => log.debug(s"${s} bytes written in ${dataId}")
                      case o: Any => throw CryoError(s"Fail to write data ${dataId}", o)
                    }
                  }
                }
            } finally {
              input.close()
            }

            transfer flatMap {
              Unit => (cryoctx.datastore ? CloseData(dataId))
            } map {
              case DataClosed(id) =>
              case o => throw CryoError(s"Fail to close data ${dataId}", o)
            } onComplete { t =>
              t match {
                case Failure(e) => log.error(s"Download job ${job.id} data has failed", e)
                case Success(_) => log.info(s"Data ${dataId} downloaded")
              }
            }
          } catch {
            case rnfe: ResourceNotFoundException => log.warn(s"Job ${job.id} is outdated. It can't be downloaded")
            case t: Throwable => log.error(s"Fail to download job ${job.id}", t)
          }
          (cryoctx.manager ? FinalizeJob(job.id)) onComplete {
            case Success(j: Job) => log.info(s"Data ${dataId} download job finalized")
            case o: Any => log.error(CryoError(s"Fail to finalize data ${dataId}", o))
          }
      }

    case RefreshJobList() =>
      val _sender = sender
      val jobList = glacier.listJobs(new ListJobsRequest()
        .withVaultName(cryoctx.vaultName))
        .getJobList
        .map(Job(_))
        .toList
      (cryoctx.manager ? UpdateJobList(jobList)) onComplete {
        case Success(JobListUpdated(jobs)) => _sender ! JobListRefreshed()
        case o: Any => _sender ! CryoError("Fail to update job list", o)
      }

    case RefreshInventory() =>
      val _sender = sender
      futureSnsTopicARN map {
        case snsTopicARN =>
          log.debug("initiateInventoryJob")
          glacier.initiateJob(new InitiateJobRequest()
            .withVaultName(cryoctx.vaultName)
            .withJobParameters(
              new JobParameters()
                .withType("inventory-retrieval")
                .withSNSTopic(snsTopicARN))).getJobId
      } map {
        case jobId =>
          log.debug("initiateInventoryJob done")
          new Job(jobId, "", new Date, InProgress(), None, "inventory")
      } flatMap {
        case job => cryoctx.manager ? AddJobs(job)
      } onComplete {
        case Success(JobsAdded(job)) => _sender ! RefreshInventoryRequested(job.head.asInstanceOf[Job])
        case o: Any => _sender ! CryoError("Fail to add refresh inventory job", o)
      }

    case DownloadArchive(archiveId) =>
      val _sender = sender
      futureSnsTopicARN map {
        case snsTopicARN =>
          glacier.initiateJob(new InitiateJobRequest()
            .withVaultName(cryoctx.vaultName)
            .withJobParameters(
              new JobParameters()
                .withArchiveId(archiveId)
                .withType("archive-retrieval")
                .withSNSTopic(snsTopicARN))).getJobId
      } flatMap {
        case jobId =>
          cryoctx.manager ? AddJobs(new Job(jobId, "", new Date, InProgress(), None, archiveId))
      } onComplete {
        case Success(JobsAdded(jobs)) => jobs.map(job => _sender ! DownloadArchiveRequested(job))
        case o: Any => _sender ! CryoError("Fail to add download archive job", o)
      }

    case UploadData(id) =>
      val _sender = sender
      (cryoctx.datastore ? GetDataStatus(id)) onComplete {
        case Success(DataStatus(_, _, _, status, size, checksum)) if status == Cached =>
          if (size < cryoctx.multipartThreshold) {
            glacier.uploadArchive(new UploadArchiveRequest()
              //.withArchiveDescription(description)
              .withVaultName(cryoctx.vaultName)
              .withChecksum(checksum)
              .withBody(new DatastoreInputStream(cryoctx, id, size, 0))
              .withContentLength(size)).getArchiveId
          } else {
            val uploadId = glacier.initiateMultipartUpload(new InitiateMultipartUploadRequest()
              //.withArchiveDescription(description)
              .withVaultName(cryoctx.vaultName)
              .withPartSize(cryoctx.partSize.toString)).getUploadId
            for (partStart <- (0L to size by cryoctx.partSize)) {
              val length = (size - partStart).min(cryoctx.partSize)
              glacier.uploadMultipartPart(new UploadMultipartPartRequest()
                .withChecksum(checksum)
                .withBody(new DatastoreInputStream(cryoctx, id, length, partStart))
                .withRange(s"${partStart}-${partStart + length}")
                .withUploadId(uploadId)
                .withVaultName(cryoctx.vaultName))
            }
            glacier.completeMultipartUpload(new CompleteMultipartUploadRequest()
              .withArchiveSize(size.toString)
              .withVaultName(cryoctx.vaultName)
              .withChecksum(checksum)
              .withUploadId(uploadId)).getArchiveId
          }
          _sender ! DataUploaded(id)
        case o: Any => _sender ! CryoError(s"Upload error : fail to get status of data ${id}", o)
      }
  }
}