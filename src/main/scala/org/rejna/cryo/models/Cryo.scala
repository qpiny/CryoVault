package org.rejna.cryo.models

import scala.collection.mutable.HashMap
import scala.collection.JavaConversions.asScalaBuffer
import scala.concurrent.duration._

import java.util.UUID
import java.io.InputStream

import akka.actor._
import akka.event._

import org.joda.time.DateTime

import org.apache.http.client.params.AuthPolicy
import org.apache.http.auth.params.AuthPNames
import org.apache.http.client.HttpClient
import com.amazonaws.AmazonWebServiceClient
import com.amazonaws.http.AmazonHttpClient
import com.amazonaws.services.glacier.AmazonGlacierClient
import com.amazonaws.services.glacier.model._
import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sqs.model.{ CreateQueueRequest, ListQueuesRequest, GetQueueAttributesRequest }
import com.amazonaws.services.sns.AmazonSNSClient
import com.amazonaws.services.sns.model.{ CreateTopicRequest, SubscribeRequest }
import ArchiveType._
import CryoStatus._

abstract class Event { val path: String }
case class AttributeChange[A](path: String, attribute: ReadAttribute[A]) extends Event
case class AttributeListChange[A](path: String, addedValues: List[A], removedValues: List[A]) extends Event

trait EventPublisher {
  def publish(event: Event)
}

trait CryoEventBus extends EventBus {
  type Event = org.rejna.cryo.models.Event
}

object Logger extends EventPublisher {
  private var _eventBus: Option[CryoEventBus] = None
  def setEventBus(eventBus: CryoEventBus) = _eventBus = Some(eventBus)

  def publish(event: Event) = {
    for (bus <- _eventBus)
      bus.publish(event)
  }
}

case object InitiateInventoryRequest
case class InitiateInventoryResponse(job: Job)
case class JobCompleted(job: Job)
case object RefreshJobList

class Cryo extends Actor with LoggingClass {
  val attributeBuilder = new AttributeBuilder(self, "/cryo")
  val inventory = context.actorFor("/user/inventory")
  val jobList = new JobList(attributeBuilder)

  
  def receive = {
    case InitiateInventoryRequest => 
       val jobId = glacier.initiateJob(new InitiateJobRequest()
      .withVaultName(Config.vaultName)
      .withJobParameters(
        new JobParameters()
          .withType("inventory-retrieval")
          .withSNSTopic(snsTopicARN))).getJobId

    log.info(s"initiateInventory(${jobId}): started")
    val job = Job(jobId, "InventoryRetrieval", "", None, new DateTime, "InProgress", false, None, None)
    jobList += jobId -> job
  
    case JobCompleted(job) =>
        log.info(s"inventoryUpdate(${job.id}): getJobOutput")
        val input = getJobOutput(job.id)
        log.info(s"inventoryUpdate(${job.id}): getJobOutput -> OK")
        val output = new MonitoredOutputStream(Cryo.attributeBuilder, "Downloading inventory",
          Files.newOutputStream(file, CREATE_NEW),
          input.available)
        try {
          log.info(s"inventoryUpdate(${job.id}): copy inventory in file")
          StreamOps.copyStream(input, output)
        } finally {
          input.close
          output.close
        }
        update(file)
      })
  }

  def __hackAddProxyAuthPref(awsClient: AmazonWebServiceClient): Unit = {
    val clientField = classOf[AmazonWebServiceClient].getDeclaredField("client")
    clientField.setAccessible(true)
    val client = clientField.get(awsClient).asInstanceOf[AmazonHttpClient]
    val httpClientField = classOf[AmazonHttpClient].getDeclaredField("httpClient")
    httpClientField.setAccessible(true)
    val httpClient = httpClientField.get(client).asInstanceOf[HttpClient]
    val l = new java.util.ArrayList[String]
    l.add(AuthPolicy.BASIC)
    httpClient.getParams.setParameter(AuthPNames.PROXY_AUTH_PREF, l)
  }
  
  
	  /* Initialize Glacier connector */
	  val glacier = new AmazonGlacierClient(Config.awsCredentials, Config.awsConfig)
	  __hackAddProxyAuthPref(glacier)
	  glacier.setEndpoint("glacier." + Config.region + ".amazonaws.com/")
	  val sqs = new AmazonSQSClient(Config.awsCredentials, Config.awsConfig)
	  __hackAddProxyAuthPref(sqs)
	  sqs.setEndpoint("sqs." + Config.region + ".amazonaws.com")
	  val sns = new AmazonSNSClient(Config.awsCredentials, Config.awsConfig)
	  __hackAddProxyAuthPref(sns)
	  sns.setEndpoint("sns." + Config.region + ".amazonaws.com")

	  var sqsQueueARN = Config.sqsQueueARN
	  var snsTopicARN = Config.sqsTopicARN
	  val sqsQueueURL = sqs.listQueues(new ListQueuesRequest(Config.sqsQueueName)).getQueueUrls.headOption.getOrElse {
	    val url = sqs.createQueue(new CreateQueueRequest().withQueueName(Config.sqsQueueName)).getQueueUrl()
	
	    var arn = sqs.getQueueAttributes(new GetQueueAttributesRequest()
	      .withQueueUrl(url)
	      .withAttributeNames("QueueArn"))
	      .getAttributes.get("QueueArn")
	    if (arn != sqsQueueARN) {
	      log.warn(s"cryo.sqs-queue-arn in config is not correct (${sqsQueueARN} should be ${arn})")
	      sqsQueueARN = arn
	    }
	
	    if (!sns.listTopics.getTopics.exists(_.getTopicArn == snsTopicARN)) {
	      arn = sns.createTopic(new CreateTopicRequest().withName(Config.snsTopicName)).getTopicArn
	      if (arn != snsTopicARN) {
	        log.warn(s"cryo.sns-topic-arn in config is not correct (${snsTopicARN} should be ${arn})")
	        snsTopicARN = arn
	      }
	    }
	
	    sns.subscribe(new SubscribeRequest()
	      .withTopicArn(snsTopicARN)
	      .withEndpoint(sqsQueueARN)
	      .withProtocol("sqs"))
	    url
	  }

  /* Glacier operations */
  def uploadArchive(input: InputStream, description: String, checksum: String): String =
    glacier.uploadArchive(new UploadArchiveRequest()
      .withArchiveDescription(description)
      .withVaultName(Config.vaultName)
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

  def initiateDownload(archive: Archive, onComplete: Job => Unit): Job = {
    val jobId = glacier.initiateJob(new InitiateJobRequest()
      .withVaultName(Config.vaultName)
      .withJobParameters(
        new JobParameters()
          .withArchiveId(archive.id)
          .withType("archive-retrieval")
          .withSNSTopic(snsTopicARN))).getJobId

    val job = Job(jobId, "ArchiveRetrieval", "", Some(archive), new DateTime, "InProgress", false, None, Some { j: Job =>
      jobList -= jobId
      onComplete(j)
    })
    jobList += jobId -> job
    job
  }

  def initiateInventory(onComplete: Job => Unit): Job = {
    val jobId = glacier.initiateJob(new InitiateJobRequest()
      .withVaultName(Config.vaultName)
      .withJobParameters(
        new JobParameters()
          .withType("inventory-retrieval")
          .withSNSTopic(snsTopicARN))).getJobId

    log.info(s"initiateInventory(${jobId}): started")
    val job = Job(jobId, "InventoryRetrieval", "", None, new DateTime, "InProgress", false, None, Some { j: Job =>
      log.info(s"initiateInventory(${jobId}): completed")
      jobList -= j.id
      onComplete(j)
    })
    jobList += jobId -> job
    job
  }

  def getJobOutput(jobId: String, range: Option[String] = None): InputStream = {
    glacier.getJobOutput(new GetJobOutputRequest()
      .withJobId(jobId)
      .withRange(range.getOrElse(null))
      .withVaultName(Config.vaultName)).getBody
  }

  def listJobs = {
    glacier.listJobs(new ListJobsRequest()
      .withVaultName(Config.vaultName))
      .getJobList
      .map(Job(_))
  }
}