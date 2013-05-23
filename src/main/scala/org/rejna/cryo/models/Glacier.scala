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

case object RefreshJobList
case object RefreshInventory
case class InventoryRequested(job: InventoryJob)
case class JobOutputRequest(jobId: String)
case class DownloadArchiveRequest(archiveId: String)
case class DownloadArchiveRequested(job: ArchiveJob)
case class UploadData(id: String)

class Glacier(config: Settings) extends Actor {

  val glacier = new AmazonGlacierClient(config.awsCredentials, config.awsConfig)
  //__hackAddProxyAuthPref(glacier)
  glacier.setEndpoint("glacier." + config.region + ".amazonaws.com/")
  //val sqs = new AmazonSQSClient(config.awsCredentials, config.awsConfig)
  //__hackAddProxyAuthPref(sqs)
  //sqs.setEndpoint("sqs." + config.region + ".amazonaws.com")
  //var sns = new AmazonSNSClient(config.awsCredentials, config.awsConfig)
  //__hackAddProxyAuthPref(sns)
  //sns.setEndpoint("sns." + config.region + ".amazonaws.com")
  //var snsTopicARN: String
  val datastore = context.actorFor("/user/datastore")
  val manager = context.actorFor("/user/manager")
  val inventory = context.actorFor("/user/inventory")
  implicit val executionContext = context.system.dispatcher
  val snsTopicARN = "XXXX" // FIXME

  def preStart = {
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
          .withVaultName(config.vaultName)).getBody
        val output = new DataStoreOutputStream(dataId)
        val buffer = Array.ofDim[Byte](config.bufferSize.intValue)
        Iterator.continually(input.read(buffer))
          .takeWhile(_ != -1)
          .foreach { output.write(buffer, 0, _) }
      }

    case RefreshJobList =>
      val jobList = glacier.listJobs(new ListJobsRequest()
        .withVaultName(config.vaultName))
        .getJobList
        .map(Job(_))
        .toList
      manager ! JobList(jobList)

    case RefreshInventory =>
      val jobId = glacier.initiateJob(new InitiateJobRequest()
        .withVaultName(config.vaultName)
        .withJobParameters(
          new JobParameters()
            .withType("inventory-retrieval")
            .withSNSTopic(snsTopicARN))).getJobId

      val job = InventoryJob(jobId, "", new DateTime, InProgress(""), None)
      manager ! AddJob(job)

    case JobOutputRequest(jobId) =>

    case DownloadArchiveRequest(archiveId) =>
      val jobId = glacier.initiateJob(new InitiateJobRequest()
        .withVaultName(config.vaultName)
        .withJobParameters(
          new JobParameters()
            .withArchiveId(archiveId)
            .withType("archive-retrieval")
            .withSNSTopic(snsTopicARN))).getJobId

      val job = ArchiveJob(jobId, "", new DateTime, InProgress(""), None, archiveId)
      manager ! AddJob(job)

    case UploadData(id) =>
      implicit val timeout = Timeout(10 seconds)
      (datastore ? GetDataStatus(id))
        .map {
          case DataStatus(_, status, size, checksum) if status == EntryStatus.Created =>
            if (size < config.multipartThreshold) {
              glacier.uploadArchive(new UploadArchiveRequest()
                //.withArchiveDescription(description)
                .withVaultName(config.vaultName)
                .withChecksum(checksum)
                .withBody(new DataStoreInputStream(id, size, 0))
                .withContentLength(size)).getArchiveId
            } else {
              val uploadId = glacier.initiateMultipartUpload(new InitiateMultipartUploadRequest()
                //.withArchiveDescription(description)
                .withVaultName(config.vaultName)
                .withPartSize(config.partSize.toString)).getUploadId
              for (partStart <- (0L to size by config.partSize)) {
                val length = (size - partStart).min(config.partSize)
                glacier.uploadMultipartPart(new UploadMultipartPartRequest()
                  .withChecksum(checksum)
                  .withBody(new DataStoreInputStream(id, length, partStart))
                  .withRange(s"${partStart}-${partStart + length}")
                  .withUploadId(uploadId)
                  .withVaultName(config.vaultName))
              }
              glacier.completeMultipartUpload(new CompleteMultipartUploadRequest()
                .withArchiveSize(size.toString)
                .withVaultName(config.vaultName)
                .withChecksum(checksum)
                .withUploadId(uploadId)).getArchiveId
            }
        }
  }
}

