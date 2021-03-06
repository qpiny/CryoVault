package org.rejna.cryo.models

import scala.collection.JavaConversions.asScalaBuffer
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.language.postfixOps
import scala.concurrent.Await
import scala.util.{ Failure, Success }

import java.nio.channels.Channels
import java.nio.ByteBuffer
import java.util.{ Date, UUID }

import akka.actor.{ Actor, Props }
import akka.util.ByteString

import com.amazonaws.services.glacier.AmazonGlacierClient
import com.amazonaws.services.glacier.model._
import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sns.AmazonSNSClient

import DataType._

case class ArchiveDescription(dataType: DataType, dataId: UUID) {
  override def toString = s"${dataType}#${dataId}"
}
object ArchiveDescription {
  val regex = "([^#]*)#(.*)".r
  def apply(desc: String) = {
    val regex(dataType, dataId) = desc
    new ArchiveDescription(DataType.withName(dataType), UUID.fromString(dataId))
  }
}

class Glacier(_cryoctx: CryoContext) extends CryoActor(_cryoctx) {

  val glacier = new AmazonGlacierClient(cryoctx.awsCredentials, cryoctx.awsConfig)
  glacier.setEndpoint("glacier." + cryoctx.region + ".amazonaws.com/")
  if (cryoctx.config.getBoolean("cryo.add-proxy-auth-pref")) HttpClientProxyHack(glacier)
  val futureSnsTopicARN = (cryoctx.notification ? GetNotificationARN()) map {
    case NotificationARN(arn) => arn
    case e: Any => throw cryoError("Fail to get notification ARN", e)
  }

  CryoEventBus.subscribe(self, "/cryo/manager#jobs")

  def receive = cryoReceive {
    case PrepareToDie() =>
      sender ! ReadyToDie()

    case AttributeListChange("/cryo/manager#jobs", addedJobs, removedJobs) =>
      for (job <- addedJobs.asInstanceOf[List[(String, Job)]].toMap.values if job.status.isSucceeded) {
        log.info(s"Job ${job.id} is completed, downloading data ${job.objectId}")

        var transfer = (cryoctx.datastore ? GetDataEntry(job.objectId))
          .eflatMap("Invalid data status", {
            case DataEntry(id, _, _, _, DataStatus.Remote, _, _) =>
              (cryoctx.datastore ? PrepareDownload(id))
          }).emap("Fail to create data", {
            case DownloadPrepared(id) =>
              log.debug(s"Data ${job.objectId} created, starting download (${id})")
              id
          })
        try {
          val input = glacier.getJobOutput(new GetJobOutputRequest()
            .withJobId(job.id)
            .withVaultName(cryoctx.vaultName)).getBody
          try {
            val buffer = Array.ofDim[Byte](cryoctx.bufferSize.intValue)
            Iterator.continually(input.read(buffer))
              .takeWhile(_ != -1)
              .foreach { nRead =>
                transfer = transfer.eflatMap("Fail to write data", {
                  case dataId =>
                    (cryoctx.datastore ? WriteData(dataId, -1, ByteString.fromArray(buffer, 0, nRead)))
                }).emap("Fail to write data", {
                  case DataWritten(dataId, _, s) =>
                    log.debug(s"${s} bytes written in ${job.objectId}")
                    dataId
                })
              }
          } finally {
            input.close()
          }

          transfer.eflatMap("Fail to close data", {
            case dataId => (cryoctx.datastore ? CloseData(dataId))
          }).onComplete({
            case Success(DataClosed(id)) => log.info(s"Data ${id} downloaded")
            case o: Any => log(cryoError(s"Download job ${job.id} data has failed", o))
          })
        } catch {
          case rnfe: ResourceNotFoundException => log.warn(s"Job ${job.id} is outdated. It can't be downloaded")
          case t: Throwable => log.error(s"Fail to download job ${job.id}", t)
        }
        (cryoctx.manager ? FinalizeJob(job.id)) onComplete {
          case Success(j: Job) => log.info(s"Data ${job.objectId} download job finalized")
          case o: Any => log(cryoError(s"Fail to finalize data ${job.objectId}", o))
        }
      }

    case RefreshJobList() =>
      val jobList = glacier.listJobs(new ListJobsRequest()
        .withVaultName(cryoctx.vaultName))
        .getJobList
        .map(Job(_))
        .toList
      (cryoctx.manager ? UpdateJobList(jobList))
        .emap("Fail to update job list", {
          case JobListUpdated(jobs) => Done()
        }).reply("Fail to refresh job list", sender)

    case RefreshInventory() =>
      futureSnsTopicARN.flatMap({
        case snsTopicARN =>
          log.debug("initiateInventoryJob")
          val jobResult = glacier.initiateJob(new InitiateJobRequest()
            .withVaultName(cryoctx.vaultName)
            .withJobParameters(
              new JobParameters()
                .withType("inventory-retrieval")
                .withSNSTopic(snsTopicARN)))
          val job = new Job(jobResult.getJobId(), "", new Date, InProgress(), None, "inventory")
          cryoctx.manager ? AddJob(job)
      }).reply("Fail to refresh inventory", sender)

    case DownloadArchive(archiveId) =>
      futureSnsTopicARN.flatMap({
        case snsTopicARN =>
          val jobResult = glacier.initiateJob(new InitiateJobRequest()
            .withVaultName(cryoctx.vaultName)
            .withJobParameters(
              new JobParameters()
                .withArchiveId(archiveId)
                .withType("archive-retrieval")
                .withSNSTopic(snsTopicARN)))
          val job = new Job(jobResult.getJobId, "", new Date, InProgress(), None, archiveId)
          cryoctx.manager ? AddJob(job)
      }).emap("Fail to add archive download job", {
        case JobAdded(job) => JobRequested(job)
      }).reply("Fail to add download archive job", sender)

    case Upload(id, dataType) =>
      val _sender = sender
      (cryoctx.datastore ? GetDataEntry(id)) onComplete {
        case Success(DataEntry(_, _, _, _, DataStatus.Readable, size, checksum)) =>
          if (size < cryoctx.multipartThreshold) {
            glacier.uploadArchive(new UploadArchiveRequest()
              .withArchiveDescription(ArchiveDescription(dataType, id).toString)
              .withVaultName(cryoctx.vaultName)
              .withChecksum(checksum)
              .withBody(new DatastoreInputStream(cryoctx, id, size, 0))
              .withContentLength(size)).getArchiveId
          } else {
            val uploadId = glacier.initiateMultipartUpload(new InitiateMultipartUploadRequest()
              .withArchiveDescription(ArchiveDescription(dataType, id).toString)
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
          _sender ! Uploaded(id)
        case o: Any => _sender ! cryoError(s"Upload error : fail to get status of data ${id}", o)
      }
  }
}