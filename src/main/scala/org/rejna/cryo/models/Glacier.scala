package org.rejna.cryo.models

import scala.collection.JavaConversions.asScalaBuffer
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.Await

import java.nio.channels.Channels
import java.nio.ByteBuffer

import akka.actor.{ Actor, Props }
import akka.pattern.ask
import akka.util.Timeout

import com.amazonaws.services.glacier.AmazonGlacierClient
import com.amazonaws.services.glacier.model._
import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sns.AmazonSNSClient

import org.joda.time.DateTime

import resource._

class CryoRequest extends Request
class CryoResponse extends Response

case class RefreshJobList() extends CryoRequest
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
      for ((jobId, attr) <- addedJobs.asInstanceOf[List[(String, Job)]]) {
        // TODO do it asynchronously
        val dataId = attr match {
          case a: ArchiveJob => a.archiveId
          case i: InventoryJob => "inventory"
        }
        for (
          input <- managed(glacier.getJobOutput(new GetJobOutputRequest()
            .withJobId(jobId)
            .withVaultName(cryoctx.vaultName)).getBody);
          output <- managed(new DataStoreOutputStream(cryoctx, dataId))
        ) {
          val buffer = Array.ofDim[Byte](cryoctx.bufferSize.intValue)
          Iterator.continually(input.read(buffer))
            .takeWhile(_ != -1)
            .foreach { output.write(buffer, 0, _) }
        }
      }

    case RefreshJobList() =>
      val jobList = glacier.listJobs(new ListJobsRequest()
        .withVaultName(cryoctx.vaultName))
        .getJobList
        .map(Job(_))
        .toList
      cryoctx.manager ! UpdateJobList(jobList)

    case RefreshInventory() =>
      val jobId = glacier.initiateJob(new InitiateJobRequest()
        .withVaultName(cryoctx.vaultName)
        .withJobParameters(
          new JobParameters()
            .withType("inventory-retrieval")
            .withSNSTopic(snsTopicARN))).getJobId

      val job = new InventoryJob(jobId, "", new DateTime, InProgress(""), None)
      cryoctx.manager ! AddJobs(job)

    //case JobOutputRequest(jobId) =>

    case DownloadArchive(archiveId) =>
      val jobId = glacier.initiateJob(new InitiateJobRequest()
        .withVaultName(cryoctx.vaultName)
        .withJobParameters(
          new JobParameters()
            .withArchiveId(archiveId)
            .withType("archive-retrieval")
            .withSNSTopic(snsTopicARN))).getJobId

      val job = new ArchiveJob(jobId, "", new DateTime, InProgress(""), None, archiveId)
      cryoctx.manager ! AddJobs(job)

    case UploadData(id) =>
      implicit val timeout = Timeout(10 seconds)
      val requester = sender
      (cryoctx.datastore ? GetDataStatus(id))
        .map {
          case DataStatus(_, _, status, size, checksum) if status == EntryStatus.Created =>
            if (size < cryoctx.multipartThreshold) {
              glacier.uploadArchive(new UploadArchiveRequest()
                //.withArchiveDescription(description)
                .withVaultName(cryoctx.vaultName)
                .withChecksum(checksum)
                .withBody(new DataStoreInputStream(cryoctx, id, size, 0))
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
                  .withBody(new DataStoreInputStream(cryoctx, id, length, partStart))
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
        }
  }
}