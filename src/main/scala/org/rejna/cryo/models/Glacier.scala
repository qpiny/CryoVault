package org.rejna.cryo.models

import scala.collection.JavaConversions.asScalaBuffer
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.Await
import scala.util.{ Failure, Success }

import java.nio.channels.Channels
import java.nio.ByteBuffer

import akka.actor.{ Actor, Props }
import akka.pattern.ask
import akka.util.{ ByteString, Timeout }

import com.amazonaws.services.glacier.AmazonGlacierClient
import com.amazonaws.services.glacier.model._
import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sns.AmazonSNSClient

import org.joda.time.DateTime

import resource._

class CryoRequest extends Request
class CryoResponse extends Response

case class RefreshJobList() extends CryoRequest
case class JobListRefreshed() extends CryoResponse
case class RefreshInventory() extends CryoRequest
case class RefreshInventoryRequested(job: InventoryJob) extends CryoResponse
case class DownloadArchive(archiveId: String) extends CryoRequest
case class DownloadArchiveRequested(job: ArchiveJob) extends CryoResponse
case class UploadData(id: String) extends CryoRequest
case class DataUploaded(id: String) extends CryoResponse

class Glacier(cryoctx: CryoContext) extends Actor with LoggingClass {

  val glacier = new AmazonGlacierClient(cryoctx.awsCredentials, cryoctx.awsConfig)
  if (cryoctx.config.getBoolean("cryo.add-proxy-auth-pref"))
    HttpClientProxyHack(glacier)
  glacier.setEndpoint("glacier." + cryoctx.region + ".amazonaws.com/")
  implicit val executionContext = context.system.dispatcher
  implicit val timeout = Timeout(10 seconds)
  val futureSnsTopicARN = (cryoctx.notification ? GetNotificationARN()) map {
    case NotificationARN(arn) => arn
    case e: Any => throw CryoError(e)
  }
  lazy val snsTopicARN = Await.result(futureSnsTopicARN, 10 seconds)

  override def preStart = {
    CryoEventBus.subscribe(self, "/cryo/manager#jobs")
  }

  def receive: Receive = CryoReceive {
    case AttributeListChange(path, addedJobs, removedJobs) if path == "/cryo/manager#jobs" =>
      val succeededJobs = addedJobs.asInstanceOf[List[(String, Job)]].flatMap {
        case (_, a: ArchiveJob) if a.status.isSucceeded => Some(a.archiveId -> a)
        case (_, i: InventoryJob) if i.status.isSucceeded => Some("inventory" -> i)
        case _: Any => None
      } toMap // remove duplicates

      succeededJobs.map {
        case (dataId, job) =>
          log.info(s"Job ${job.id} is completed, downloading data ${dataId}")
          var transfer = (cryoctx.datastore ? GetDataStatus(dataId)) map {
            case DataStatus(_, _, _, status, _, _) if status != EntryStatus.Creating => // TODO will be Loading when implemented
            case DataNotFoundError(_, _, _) =>
            case o: Any => throw new CryoError(s"Invalid data status ${o}")
          } flatMap {
            Unit => (cryoctx.datastore ? CreateData(Some(dataId), job.description))
          } map {
            case DataCreated(dataId) => log.debug(s"Data ${dataId} created, starting download")
            case o: Any => throw CryoError(o)
          }
          for (
            input <- managed(glacier.getJobOutput(new GetJobOutputRequest()
              .withJobId(job.id)
              .withVaultName(cryoctx.vaultName)).getBody)
          ) {
            val buffer = Array.ofDim[Byte](cryoctx.bufferSize.intValue)
            Iterator.continually(input.read(buffer))
              .takeWhile(_ != -1)
              .foreach { nRead =>
                transfer = transfer.flatMap { Unit =>
                  (cryoctx.datastore ? WriteData(dataId, -1, ByteString.fromArray(buffer, 0, nRead))) map {
                    case DataWritten(_, _, s) => log.debug(s"${s} bytes written in ${dataId}")
                    case o: Any => throw CryoError(o)
                  }
                }
              }
          }

          transfer flatMap {
            Unit => (cryoctx.datastore ? CloseData(dataId))
          } map {
            case DataClosed(id) =>
            case o => throw CryoError(o)
          } onComplete { t =>
            t match {
              case Failure(e) => log.error(s"Download job ${job.id} data has failed", e)
              case Success(_) => log.info(s"Data ${dataId} downloaded")
            }
            (cryoctx.manager ? FinalizeJob(job.id)) map {
              case JobFinalized(_) => log.info(s"Data ${dataId} downloaded")
              case o: Any => log.debug("Unexpected error", CryoError(o))
            }
          }
      }

    case RefreshJobList() =>
      val requester = sender
      val jobList = glacier.listJobs(new ListJobsRequest()
        .withVaultName(cryoctx.vaultName))
        .getJobList
        .map(Job(_))
        .toList
      cryoctx.manager ? UpdateJobList(jobList) map {
        case JobListUpdated(jobs) => requester ! JobListRefreshed()
        case o: Any => requester ! CryoError(o)
      }

    case RefreshInventory() =>
      val requester = sender
      val jobId = glacier.initiateJob(new InitiateJobRequest()
        .withVaultName(cryoctx.vaultName)
        .withJobParameters(
          new JobParameters()
            .withType("inventory-retrieval")
            .withSNSTopic(snsTopicARN))).getJobId

      val job = new InventoryJob(jobId, "", new DateTime, InProgress(""), None)
      cryoctx.manager ? AddJobs(job) map {
        case JobsAdded(_) => requester ! RefreshInventoryRequested(job)
        case o: Any => requester ! CryoError(o)
      }

    case DownloadArchive(archiveId) =>
      val requester = sender
      val jobId = glacier.initiateJob(new InitiateJobRequest()
        .withVaultName(cryoctx.vaultName)
        .withJobParameters(
          new JobParameters()
            .withArchiveId(archiveId)
            .withType("archive-retrieval")
            .withSNSTopic(snsTopicARN))).getJobId

      val job = new ArchiveJob(jobId, "", new DateTime, InProgress(""), None, archiveId)
      cryoctx.manager ? AddJobs(job) map {
        case JobsAdded(_) => requester ! DownloadArchiveRequested(job)
        case o: Any => requester ! CryoError(o)
      }

    case UploadData(id) =>
      val requester = sender
      (cryoctx.datastore ? GetDataStatus(id)) map {
        case DataStatus(_, _, _, status, size, checksum) if status == EntryStatus.Created =>
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
          requester ! DataUploaded(id)
        case o: Any => requester ! CryoError(o)
      }
  }
}