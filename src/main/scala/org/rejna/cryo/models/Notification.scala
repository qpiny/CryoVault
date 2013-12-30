package org.rejna.cryo.models

import scala.collection.JavaConversions._ //{ asScalaBuffer, mapAsJavaMap }
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.{ Success, Failure }

import java.util.Date

import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sqs.model._
import com.amazonaws.services.sns.AmazonSNSClient
import com.amazonaws.services.sns.model._

sealed abstract class NotificationRequest extends Request
sealed abstract class NotificationResponse extends Response
sealed class NotificationError(message: String, cause: Throwable) extends CryoError(classOf[Notification].getName, message, cause = cause)

case class GetNotification() extends NotificationRequest
case class NotificationGot() extends NotificationResponse
case class GetNotificationARN() extends NotificationRequest
case class NotificationARN(arn: String) extends NotificationResponse

case class NotificationMessage(
  notificationType: String,
  messageId: String,
  topicArn: String,
  message: String,
  timestamp: Date)
// SignatureVersion(1)
// Signature(Kss0qWBDa...)
// SigningCertURL(https://sns.eu-west-1.amaz...)
// UnsubscribeURLhttps://sns.eu-west-1.amaz...)

trait Notification { self: CryoActor =>
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

class QueueNotification(_cryoctx: CryoContext) extends CryoActor(_cryoctx) with Notification {
  val sqs = new AmazonSQSClient(cryoctx.awsCredentials, cryoctx.awsConfig)
  if (cryoctx.config.getBoolean("cryo.add-proxy-auth-pref"))
    HttpClientProxyHack(sqs)
  sqs.setEndpoint("sqs." + cryoctx.region + ".amazonaws.com")
  val (queueUrl, queueArn) = getOrCreateQueue

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
    removeMessage(getMessages.map(_.getReceiptHandle))
    context.system.scheduler.schedule(0 second, cryoctx.queueRequestInterval, self, GetNotification())(context.system.dispatcher)
  }

  def getMessages = {
    sqs.receiveMessage(new ReceiveMessageRequest(queueUrl).withMaxNumberOfMessages(10)).getMessages
  }

  def removeMessage2(receiptHandles: Iterable[String]) = {
    if (!receiptHandles.isEmpty) {
      log.debug(s"Remove ${receiptHandles.size} message(s) from queue")
      val request = receiptHandles.zipWithIndex map {
        case (receiptHandle, i) => new DeleteMessageBatchRequestEntry(i.toString, receiptHandle)
      }
      val response = sqs.deleteMessageBatch(new DeleteMessageBatchRequest(queueUrl, request.toList))
      for (failure <- response.getFailed)
        log.debug(s"Fail to remove message ${failure.getId}: ${failure.getCode} ${failure.getMessage} (${if (failure.isSenderFault) "client" else "server"} fault")
      for (success <- response.getSuccessful)
        log.debug(s"Message ${success.getId} has been successfully removed")
    }
  }

  def removeMessage(receiptHandles: Iterable[String]) = {
    for (rh <- receiptHandles)
      try {
        sqs.deleteMessage(new DeleteMessageRequest(queueUrl, rh))
        log.debug(s"Message ${rh} removed")
      } catch {
        case e: Exception => log.warn("Fail to remove message in queue", e)
      }
  }
  def receive = cryoReceive {
    case PrepareToDie() => sender ! ReadyToDie()

    case GetNotification() =>
      val messages = getMessages.distinct
      log.debug(s"${messages.size} message(s) read from SQS")
      if (!messages.isEmpty) {
        val jobsReceipt = messages map {
          case message =>
            val body = message.getBody
            log.debug("Receive notification message :" + message)
            val notificationMessage = Json.read[NotificationMessage](body)
            Json.read[Job](notificationMessage.message) -> message.getReceiptHandle
        } toMap

        Future.sequence(jobsReceipt.keySet.map(jobId => (cryoctx.manager ? AddJob(jobId))))
          .emap("", {
            case l =>
              val (success, failure) = l.partition(_.isInstanceOf[JobAdded])
              if (!failure.isEmpty)
                log.error(s"Fail to add jobs from notification : ${failure}")
              val addedJobIds = success.asInstanceOf[Set[JobAdded]].map(_.job)
              removeMessage(addedJobIds.map(jobsReceipt.apply(_)))
              NotificationGot()
          }).recover({
            case t: Any => log(CryoError("Fail to remove notification message", t))
          }).reply("Fail to add job from notirication", sender)
      } else
        sender ! NotificationGot()

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