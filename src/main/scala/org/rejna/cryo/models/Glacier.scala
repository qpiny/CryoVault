package org.rejna.cryo.models

import scala.collection.JavaConversions.asScalaBuffer
import scala.concurrent.duration._
import scala.language.postfixOps

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

class CryoRequest extends Request
class CryoResponse extends Response

case object RefreshJobList extends CryoRequest
case object RefreshInventory extends CryoRequest
case class RefreshInventoryRequested(job: InventoryJob) extends CryoResponse
//case class JobOutputRequest(jobId: String)
case class DownloadArchive(archiveId: String) extends CryoRequest
case class DownloadArchiveRequested(job: ArchiveJob) extends CryoResponse
case class UploadData(id: String) extends CryoRequest
case class DataUploaded(id: String) extends CryoResponse

class Glacier(cryoctx: CryoContext) extends Actor {

  val glacier = new AmazonGlacierClient(cryoctx.awsCredentials, cryoctx.awsConfig)
  //__hackAddProxyAuthPref(glacier)
  glacier.setEndpoint("glacier." + cryoctx.region + ".amazonaws.com/")
  //val sqs = new AmazonSQSClient(cryoctx.awsCredentials, cryoctx.awsConfig)
  //__hackAddProxyAuthPref(sqs)
  //sqs.setEndpoint("sqs." + cryoctx.region + ".amazonaws.com")
  //var sns = new AmazonSNSClient(cryoctx.awsCredentials, cryoctx.awsConfig)
  //__hackAddProxyAuthPref(sns)
  //sns.setEndpoint("sns." + cryoctx.region + ".amazonaws.com")
  //var snsTopicARN: String
  implicit val executionContext = context.system.dispatcher
  val snsTopicARN = "XXXX" // FIXME

  override def preStart = {
    CryoEventBus.subscribe(self, "/user/manager#jobs")
  }

  def receive: Receive = {
    case AttributeListChange(path, addedJobs, removedJobs) if path == "/user/manager#jobs" =>
      for ((jobId, attr) <- addedJobs.asInstanceOf[List[(String, Job)]]) {
        // TODO do it asynchronously
        val dataId = attr match {
          case a: ArchiveJob => a.archiveId
          case i: InventoryJob => "inventory"
        }
        val input = glacier.getJobOutput(new GetJobOutputRequest()
          .withJobId(jobId)
          .withVaultName(cryoctx.vaultName)).getBody
        val output = new DataStoreOutputStream(cryoctx, dataId)
        val buffer = Array.ofDim[Byte](cryoctx.bufferSize.intValue)
        Iterator.continually(input.read(buffer))
          .takeWhile(_ != -1)
          .foreach { output.write(buffer, 0, _) }
      }

    case RefreshJobList =>
      val jobList = glacier.listJobs(new ListJobsRequest()
        .withVaultName(cryoctx.vaultName))
        .getJobList
        .map(Job(_))
        .toList
      cryoctx.manager ! JobList(jobList)

    case RefreshInventory =>
      val jobId = glacier.initiateJob(new InitiateJobRequest()
        .withVaultName(cryoctx.vaultName)
        .withJobParameters(
          new JobParameters()
            .withType("inventory-retrieval")
            .withSNSTopic(snsTopicARN))).getJobId

      val job = new InventoryJob(jobId, "", new DateTime, InProgress(""), None)
      cryoctx.manager ! AddJob(job)

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
      cryoctx.manager ! AddJob(job)

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