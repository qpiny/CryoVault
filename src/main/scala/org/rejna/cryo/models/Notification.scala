package org.rejna.cryo.models

import scala.collection.JavaConversions._ //{ asScalaBuffer, mapAsJavaMap }
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{ Success, Failure }

import net.liftweb.json.{ Serialization, NoTypeHints }

import org.joda.time.DateTime

import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sqs.model._
import com.amazonaws.services.sns.AmazonSNSClient
import com.amazonaws.services.sns.model._

sealed abstract class NotificationRequest extends Request
sealed abstract class NotificationResponse extends Response
sealed class NotificationError(message: String, cause: Throwable) extends CryoError(message, cause)

case class GetNotification() extends NotificationRequest
case class GetNotificationARN() extends NotificationRequest
case class NotificationARN(arn: String) extends NotificationResponse

case class NotificationMessage(
  notificationType: String,
  messageId: String,
  topicArn: String,
  message: String,
  timestamp: DateTime)
// SignatureVersion(1)
// Signature(Kss0qWBDa...)
// SigningCertURL(https://sns.eu-west-1.amaz...)
// UnsubscribeURLhttps://sns.eu-west-1.amaz...)

abstract class Notification(val cryoctx: CryoContext) extends CryoActor {
  val sns = new AmazonSNSClient(cryoctx.awsCredentials, cryoctx.awsConfig)
  if (cryoctx.config.getBoolean("cryo.add-proxy-auth-pref"))
    HttpClientProxyHack(sns)
  sns.setEndpoint("sns." + cryoctx.region + ".amazonaws.com")
  val notificationArn = getOrCreateQueue

  private def getOrCreateQueue: String = {
    if (!sns.listTopics.getTopics.exists(_.getTopicArn == cryoctx.snsTopicARN)) {
      log.warn(s"Notification topic ${cryoctx.snsTopicARN} doesn't exist")
      log.warn("Setting up notification topic")
      val arn = sns.createTopic(new CreateTopicRequest(cryoctx.snsTopicName)).getTopicArn
      if (arn != cryoctx.snsTopicARN) {
        log.warn(s"cryo.sns-topic-arn in config is not correct (${cryoctx.snsTopicARN} should be ${arn})")
      }
      arn
    } else
      cryoctx.snsTopicARN
  }
}

class QueueNotification(cryoctx: CryoContext) extends Notification(cryoctx) {
  implicit val formats = JsonSerialization.format

  val sqs = new AmazonSQSClient(cryoctx.awsCredentials, cryoctx.awsConfig)
  if (cryoctx.config.getBoolean("cryo.add-proxy-auth-pref"))
    HttpClientProxyHack(sqs)
  sqs.setEndpoint("sqs." + cryoctx.region + ".amazonaws.com")
  log.debug("getQueue(url&arn)")
  val (queueUrl, queueArn) = getOrCreateQueue
  log.debug("getQueue(url&arn) done")

  private def getOrCreateQueue: (String, String) = {
    sqs.listQueues(new ListQueuesRequest(cryoctx.sqsQueueName)).getQueueUrls.headOption match {
      case None =>
        log.warn(s"Notification queue ${cryoctx.sqsQueueName} doesn't exist")
        log.warn("Setting up notification queue")

        val url = sqs.createQueue(
          new CreateQueueRequest(cryoctx.sqsQueueName)
            .withAttributes(Map( // TODO put attributes in config
              "DelaySeconds" -> "0",
              "MaximumMessageSize" -> "65536",
              "MessageRetentionPeriod" -> (4 days).toSeconds.toString,
              //"Policy" -> ??
              "ReceiveMessageWaitTimeSeconds" -> "0",
              "VisibilityTimeout" -> "30"))).getQueueUrl()

        var arn = sqs.getQueueAttributes(new GetQueueAttributesRequest(url)
          .withAttributeNames("QueueArn"))
          .getAttributes.get("QueueArn")
        if (arn != cryoctx.sqsQueueARN) {
          log.warn(s"cryo.sqs-queue-arn in config is not correct (${cryoctx.sqsQueueARN} should be ${arn})")
        }
        (url, arn)
      case Some(url) =>
        (url, cryoctx.sqsQueueName)
    }
  }

  override def preStart = {
    // Remove previous messages
    log.debug("request message from queue")
    val oldMessageList = sqs.receiveMessage(new ReceiveMessageRequest(queueUrl).withMaxNumberOfMessages(10)).getMessages.distinct map {
      case message =>
        log.debug(s"Message in queue : ${message.getMessageId} / ${message.getReceiptHandle}")
        new DeleteMessageBatchRequestEntry(message.getMessageId, message.getReceiptHandle)
    }
    log.debug("request message from queue done")
    if (oldMessageList.size > 0)
      sqs.deleteMessageBatch(new DeleteMessageBatchRequest(queueUrl, oldMessageList))
    context.system.scheduler.schedule(0 second, cryoctx.queueRequestInterval, self, GetNotification())(context.system.dispatcher)
    log.debug("Notification actor is ready")
  }

  def cryoReceive = {
    case GetNotification() =>
      val jobs = sqs.receiveMessage(new ReceiveMessageRequest(queueUrl).withMaxNumberOfMessages(10)).getMessages map {
        case message =>
          val body = message.getBody
          log.debug("Receive notification message :" + message)
          val notificationMessage = Serialization.read[NotificationMessage](body)
          Serialization.read[Job](notificationMessage.message) -> message.getReceiptHandle
      } toMap

      cryoctx.manager ? AddJobs(jobs.keySet.toList) map {
        case JobsAdded(addedJobs) =>
          sqs.deleteMessageBatch(new DeleteMessageBatchRequest(
            queueUrl,
            addedJobs.map(j => new DeleteMessageBatchRequestEntry(j.id, jobs(j)))))
      } onComplete {
        case Success(_) => log.info("Notification messages have been removed")
        case Failure(e) => log.info("An error has occured while removing notification message", e)
      }

    case GetNotificationARN() =>
      sender ! NotificationARN(notificationArn)
  }
}

/*
{
  "Type" : "Notification",
  "MessageId" : "ac1ae29d-d193-553b-9a97-a88dc9ea701e",
  "TopicArn" : "arn:aws:sns:eu-west-1:235715319590:GlacierNotificationTopic",
  "Message" : "{\"Action\":\"InventoryRetrieval\",\"ArchiveId\":null,\"ArchiveSHA256TreeHash\":null,\"ArchiveSizeInBytes\":null,\"Completed\":true,\"CompletionDate\":\"2013-05-17T11:36:25.669Z\",\"CreationDate\":\"2013-05-17T07:36:17.140Z\",\"InventorySizeInBytes\":1159,\"JobDescription\":null,\"JobId\":\"Vg6tHfPPIo7HrsY15dMwi-E0dGmHFM8qAwvjtYP8jfbkHYuoXjB-tvH9f6oI0U8B_zwhpZ8eEFv0b3Yzp6cYX-OeMNex\",\"RetrievalByteRange\":null,\"SHA256TreeHash\":null,\"SNSTopic\":\"arn:aws:sns:eu-west-1:235715319590:GlacierNotificationTopic\",\"StatusCode\":\"Succeeded\",\"StatusMessage\":\"Succeeded\",\"VaultARN\":\"arn:aws:glacier:eu-west-1:235715319590:vaults/cryo\"}",
  "Timestamp" : "2013-05-17T11:36:25.752Z",
  "SignatureVersion" : "1",
  "Signature" : "Kss0qWBDaYq7ANfFmweyWnzqo/dTaq4Ql3jKK7Lm/4fsfbv0OaFFnG5yNGaswxBVK90vDxiwLc9w2TF7OaEgqRHcjD2GCx4YFqdatY2Px8WziBm5dpLhWcuvqJzxxbpV5SH7qhqaZUrESi5IJxnNfLLWUYtlVhzlCdpQr8Kf3Ds=",
  "SigningCertURL" : "https://sns.eu-west-1.amazonaws.com/SimpleNotificationService-f3ecfb7224c7233fe7bb5f59f96de52f.pem",
  "UnsubscribeURL" : "https://sns.eu-west-1.amazonaws.com/?Action=Unsubscribe&SubscriptionArn=arn:aws:sns:eu-west-1:235715319590:GlacierNotificationTopic:65e5dbb6-ee7c-484c-8a40-d0641c4ace49"
}
*/