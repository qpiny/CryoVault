package org.rejna.cryo.models

import scala.collection.JavaConversions.asScalaBuffer
import scala.concurrent.duration._
import scala.language.postfixOps

import java.nio.channels.Channels

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
case class InventoryResponse(inventory: InventoryMessage)
case class JobOutputRequest(jobId: String)
case class DownloadArchiveRequest(archiveId: String)
case class DownloadArchiveRequested(job: ArchiveJob)
case class UploadData(id: String)

class Glacier(config: Settings) extends Actor {

  var glacier: AmazonGlacierClient
  var sqs: AmazonSQSClient
  var sns: AmazonSNSClient
  var snsTopicARN: String
  val datastore = context.actorFor("/user/datastore")
  val manager = context.actorFor("/user/manager")
  val inventory = context.actorFor("/user/inventory")
  implicit val executionContext = context.system.dispatcher

  def preStart = {
    glacier = new AmazonGlacierClient(config.awsCredentials, config.awsConfig)
    //__hackAddProxyAuthPref(glacier)
    glacier.setEndpoint("glacier." + config.region + ".amazonaws.com/")
    sqs = new AmazonSQSClient(config.awsCredentials, config.awsConfig)
    //__hackAddProxyAuthPref(sqs)
    sqs.setEndpoint("sqs." + config.region + ".amazonaws.com")
    sns = new AmazonSNSClient(config.awsCredentials, config.awsConfig)
    //__hackAddProxyAuthPref(sns)
    sns.setEndpoint("sns." + config.region + ".amazonaws.com")
  }

  def receive: Receive = {
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
      val stream = glacier.getJobOutput(new GetJobOutputRequest()
        .withJobId(jobId)
        .withVaultName(config.vaultName)).getBody

      val channel = Channels.newChannel(stream)
      context.actorOf(Props(classOf[SourceActor], channel, datastore))

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
       glacier.uploadArchive(new UploadArchiveRequest()
      //.withArchiveDescription(description)
      .withVaultName(config.vaultName)
      .withChecksum(checksum)
      .withBody(input)
      .withContentLength(input.available)).getArchiveId
      implicit val timeout = Timeout(10 seconds)
      (datastore ? GetDataStatus(id))
        .map {
          case DataStatus(status, size) =>
            if (status == EntryStatus.Created) {
              
            }
            0
        }

  }
  
  def uploadArchive(data: InputStream, description: String, checksum: String): String =
    glacier.uploadArchive(new UploadArchiveRequest()
      .withArchiveDescription(description)
      .withVaultName(config.vaultName)
      .withChecksum(checksum)
      .withBody(input)
      .withContentLength(input.available)).getArchiveId

  def initiateMultipartUpload(description: String): String =
    glacier.initiateMultipartUpload(new InitiateMultipartUploadRequest()
      .withArchiveDescription(description)
      .withVaultName(Config.vaultName)
      .withPartSize(Config.partSize.toString)).getUploadId

  def uploadMultipartPart(uploadId: String, input: InputStream, range: String, checksum: String): Unit =
    glacier.uploadMultipartPart(new UploadMultipartPartRequest()
      .withChecksum(checksum)
      .withBody(input)
      .withRange(range)
      .withUploadId(uploadId)
      .withVaultName(Config.vaultName))

  def completeMultipartUpload(uploadId: String, size: Long, checksum: String): String =
    glacier.completeMultipartUpload(new CompleteMultipartUploadRequest()
      .withArchiveSize(size.toString)
      .withVaultName(Config.vaultName)
      .withChecksum(checksum)
      .withUploadId(uploadId)).getArchiveId
}

