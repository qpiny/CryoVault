package org.rejna.cryo.models

import scala.collection.mutable.HashMap
import scala.collection.JavaConversions._
import scala.io.Source
import scala.concurrent.duration._
import scalax.io.Resource
import java.io.{ File, FileOutputStream }
import java.util.UUID
import akka.actor._
import akka.event._
import akka.pattern.{ ask, pipe }
import akka.util.Subclassification
import java.io.InputStream
import com.amazonaws.auth.policy.{ Policy, Principal, Statement }
import com.amazonaws.auth.policy.Statement.Effect
import com.amazonaws.auth.policy.actions.SQSActions
import com.amazonaws.services.glacier.{ AmazonGlacier, AmazonGlacierClient, TreeHashGenerator }
import com.amazonaws.services.glacier.model._
import com.amazonaws.services.s3.internal.InputSubstream
import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sqs.model.{ CreateQueueRequest, ListQueuesRequest, GetQueueAttributesRequest, SetQueueAttributesRequest, ReceiveMessageRequest }
import com.amazonaws.services.sns.AmazonSNSClient
import com.amazonaws.services.sns.model.{ CreateTopicRequest, SubscribeRequest }
import com.amazonaws.{ AmazonWebServiceRequest, ResponseMetadata, ClientConfiguration }
import com.amazonaws.auth.AWSCredentials
import org.joda.time.{ DateTime, Interval }
import ArchiveType._
import CryoStatus._
import CryoJson._
import org.rejna.cryo.web.ResponseEvent

class Event(path: String)
case class AttributeChange[A](path: String, attribute: ReadAttribute[A]) extends Event(path)
case class AttributeListChange[A](path: String, addedValues: List[A], removedValues: List[A]) extends Event(path)
case class Log(path: String, message: String) extends Event(path)

sealed abstract class LogLevel(val path: String) {
  lazy val name = getClass.getSimpleName
}
object Fatal extends LogLevel("Fatal")
object Error extends LogLevel("Fatal/Error")
object Warn  extends LogLevel("Fatal/Error/Warn")
object Info  extends LogLevel("Fatal/Error/Warn/Info")
object Debug extends LogLevel("Fatal/Error/Warn/Info/Debug")
object Trace extends LogLevel("Fatal/Error/Warn/Info/Debug/Trace")
object Log {
  def apply(level: LogLevel, message: String): Log = Log(level.path, message)
}

trait EventPublisher {
  def publish(event: Event)
}

trait LoggingClass {
  lazy val log = org.slf4j.LoggerFactory.getLogger(this.getClass)
}

object Cryo extends EventPublisher {
  val system = ActorSystem("cryo") // FIXME (use cryoweb system)
  //val actor = system.actorOf(Props(new CryoActor(this)), name = "cryo")
  val inventory = new Inventory()

  val glacier = new AmazonGlacierClient(Config.awsCredentials);
  glacier.setEndpoint("https://glacier." + Config.region + ".amazonaws.com/")
  val sqs = new AmazonSQSClient(Config.awsCredentials)
  sqs.setEndpoint("https://sqs." + Config.region + ".amazonaws.com")
  val sns = new AmazonSNSClient(Config.awsCredentials)
  sns.setEndpoint("https://sns." + Config.region + ".amazonaws.com")

  var sqsQueueARN = Config.sqsQueueARN
  var snsTopicARN = Config.sqsTopicARN
  val sqsQueueURL = sqs.listQueues(new ListQueuesRequest(Config.sqsQueueName)).getQueueUrls.headOption.getOrElse {
    val url = sqs.createQueue(new CreateQueueRequest().withQueueName(Config.sqsQueueName)).getQueueUrl()

    var arn = sqs.getQueueAttributes(new GetQueueAttributesRequest()
      .withQueueUrl(url)
      .withAttributeNames("QueueArn"))
      .getAttributes.get("QueueArn")
    if (arn != sqsQueueARN) {
      println("WARNING : sqsQueueARN in config is not correct (%s should be %s)".format(sqsQueueARN, arn))
      sqsQueueARN = arn
    }

    if (!sns.listTopics.getTopics.exists(_.getTopicArn == snsTopicARN)) {
      arn = sns.createTopic(new CreateTopicRequest().withName(Config.snsTopicName)).getTopicArn
      if (arn != snsTopicARN) {
        println("WARNING : snsTopicARN in config is not correct (%s should be %s)".format(snsTopicARN, arn))
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

  private val _catalog = HashMap[Hash, BlockLocation]()

  def catalog = _catalog.toMap

  def updateCatalog(entries: Map[Hash, BlockLocation]) = _catalog ++ entries

  def getOrUpdateBlockLocation(hash: Hash, createBlockLocation: => BlockLocation): BlockLocation =
    _catalog.get(hash) getOrElse {
      var bl = createBlockLocation
      _catalog += hash -> bl
      bl
    }

  //lazy val eventBus = new CryoEventBus
  lazy val attributeBuilder = new AttributeBuilder(this, "/cryo")

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
      .withPartSize(Config.partSize.value.toString)).getUploadId

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

//class CryoEventBus extends EventBus with SubchannelClassification {
//  type Event = org.rejna.cryo.web.ResponseEvent
//  type Classifier = String
//  type Subscriber = ActorRef
//
//  protected def classify(event: ResponseEvent) = event.path
//  protected def subclassification = new Subclassification[Classifier] {
//    def isEqual(x: Classifier, y: Classifier) = x == y
//    def isSubclass(x: Classifier, y: Classifier) = x.startsWith(y)
//  }
//
//  protected def publish(event: Event, subscriber: Subscriber): Unit = subscriber ! event
//}