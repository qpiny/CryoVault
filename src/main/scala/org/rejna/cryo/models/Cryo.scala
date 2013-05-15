package org.rejna.cryo.models

import scala.collection.mutable.HashMap
import scala.collection.JavaConversions._
import scala.concurrent.duration._

import java.util.UUID
import java.io.InputStream

import akka.actor._
import akka.event._

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

object Cryo extends EventPublisher with LoggingClass {
  val system = ActorSystem("cryo")

  private var _eventBus: Option[CryoEventBus] = None
  def setEventBus(eventBus: CryoEventBus) = _eventBus = Some(eventBus)

  def publish(event: Event) = {
    for (bus <- _eventBus)
      bus.publish(event)
  }

  lazy val attributeBuilder = new AttributeBuilder(this, "/cryo")

  val inventory = new Inventory()

  lazy val glacier = new AmazonGlacierClient(Config.awsCredentials);
  glacier.setEndpoint("https://glacier." + Config.region + ".amazonaws.com/")
  lazy val sqs = new AmazonSQSClient(Config.awsCredentials)
  sqs.setEndpoint("https://sqs." + Config.region + ".amazonaws.com")
  lazy val sns = new AmazonSNSClient(Config.awsCredentials)
  sns.setEndpoint("https://sns." + Config.region + ".amazonaws.com")

  var sqsQueueARN = Config.sqsQueueARN
  var snsTopicARN = Config.sqsTopicARN
  lazy val sqsQueueURL = sqs.listQueues(new ListQueuesRequest(Config.sqsQueueName)).getQueueUrls.headOption.getOrElse {
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

  var jobs = Map[String, String => Unit]()

  def newArchive(archiveType: ArchiveType, id: String) = inventory.newArchive(archiveType, id)

  def newArchive(archiveType: ArchiveType): LocalArchive = inventory.newArchive(archiveType, UUID.randomUUID.toString)

  def newArchive: LocalArchive = newArchive(Data)

  def migrate(archive: LocalArchive, newId: String, size: Long, hash: Hash) = inventory.migrate(archive, newId, size, hash)

  def migrate(snapshot: LocalSnapshot, archive: RemoteArchive) = inventory.migrate(snapshot, archive)

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

  def initiateDownload(archiveId: String, onComplete: String => Unit): String = {
    val jobId = glacier.initiateJob(new InitiateJobRequest()
      .withVaultName(Config.vaultName)
      .withJobParameters(
        new JobParameters()
          .withArchiveId(archiveId)
          .withType("archive-retrieval")
          .withSNSTopic(snsTopicARN))).getJobId

    jobs = jobs + (jobId -> {
      jobs = jobs - jobId
      onComplete
    })
    jobId
  }

  def initiateInventory(onComplete: String => Unit): String = {
    val jobId = glacier.initiateJob(new InitiateJobRequest()
      .withVaultName(Config.vaultName)
      .withJobParameters(
        new JobParameters()
          .withType("inventory-retrieval")
          .withSNSTopic(snsTopicARN))).getJobId

    jobs = jobs + (jobId -> {
      jobs = jobs - jobId
      onComplete
    })
    jobId
  }

  def getJobOutput(jobId: String, range: Option[String] = None): InputStream =
    glacier.getJobOutput(new GetJobOutputRequest()
      .withJobId(jobId)
      .withRange(range.getOrElse(null))
      .withVaultName(Config.vaultName)).getBody

}