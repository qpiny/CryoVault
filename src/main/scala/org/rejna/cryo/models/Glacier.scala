package org.rejna.cryo.models

import scala.collection.JavaConversions.asScalaBuffer

import java.nio.channels.Channels

import akka.actor.{ Actor, Props }
import akka.io._

import com.amazonaws.services.glacier.AmazonGlacierClient
import com.amazonaws.services.glacier.model.{ ListJobsRequest, InitiateJobRequest, JobParameters, GetJobOutputRequest }
import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sns.AmazonSNSClient

import org.joda.time.DateTime

case object ListJobRequest
case class ListJobResponse(jobs: List[Job])
case object InventoryRequest
case class InventoryRequested(job: InventoryJob)
case class InventoryResponse(inventory: InventoryMessage)
case class JobOutputRequest(jobId: String)
case class DownloadArchiveRequest(archiveId: String)
case class DownloadArchiveRequested(job: ArchiveJob)

class Glacier(config: Settings) extends Actor {

  var glacier: AmazonGlacierClient
  var sqs: AmazonSQSClient
  var sns: AmazonSNSClient
  var snsTopicARN: String
  val datastore = context.actorFor("/user/datastore")

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
    case ListJobRequest =>
      val jobList = glacier.listJobs(new ListJobsRequest()
        .withVaultName(config.vaultName))
        .getJobList
        .map(Job(_))
        .toList
      sender ! ListJobResponse(jobList)

    case InventoryRequest =>
      val jobId = glacier.initiateJob(new InitiateJobRequest()
        .withVaultName(config.vaultName)
        .withJobParameters(
          new JobParameters()
            .withType("inventory-retrieval")
            .withSNSTopic(snsTopicARN))).getJobId

      val job = InventoryJob(jobId, "", new DateTime, InProgress(""), None, sender)
      sender ! InventoryRequested(job)

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

      val job = ArchiveJob(jobId, "", new DateTime, InProgress(""), None, archiveId, sender)
      sender ! DownloadArchiveRequested(job)
  }
}

