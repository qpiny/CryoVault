package models

import scala.collection.mutable.HashMap
import scala.collection.JavaConversions._
import scala.io.Source

import scalax.io.Resource

import java.io.{ File, FileOutputStream }
import java.util.UUID

import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.json._

import akka.actor._
import akka.event._
import akka.pattern.{ ask, pipe }
import akka.util.{ Timeout, Subclassification, Duration }
import akka.util.duration._

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

class Cryo {
  val actor = Akka.system.actorOf(Props(new CryoActor(this)), name = "cryo")
  val inventory = new Inventory()(this)

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

  lazy val eventBus = new CryoEventBus
  lazy val attributeBuilder = new AttributeBuilder(eventBus, "/cryo")

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

class CryoActor(cryo: Cryo) extends Actor {

  def receive = {
    case Subscribe(subscription) =>
      cryo.eventBus.subscribe(sender, subscription)
    case Unsubscribe(subscription) =>
      cryo.eventBus.unsubscribe(sender, subscription)
    case CreateSnapshot =>
      val snapshot = cryo.newArchive(Index)
      sender ! SnapshotCreated(snapshot.id)
    case GetArchiveList =>
      sender ! ArchiveList(cryo.inventory.archives.values.toList)
    case GetSnapshotList =>
      sender ! SnapshotList(cryo.inventory.snapshots.values.toList)
    case UpdateInventory(maxAge) =>
      cryo.inventory.update(maxAge)
    case GetSnapshotFiles(snapshotId, directory) => {
      val snapshot = cryo.inventory.snapshots(snapshotId)
      val files = snapshot match {
        case ls: LocalSnapshot => ls.files()
        case rs: RemoteSnapshot => rs.remoteFiles.map(_.file.toString)
      }
      val dir = new File(Config.baseDirectory, directory)
      sender ! new SnapshotFiles(snapshotId, directory, getDirectoryContent(dir, files, snapshot.fileFilters)) //fe.toList)
    }
    case UpdateSnapshotFileFilter(snapshotId, directory, filter) =>
      val snapshot = cryo.inventory.snapshots(snapshotId)
      snapshot match {
        case ls: LocalSnapshot => ls.fileFilters(directory) = filter
        case _ => println("UpdateSnapshotFileFilter is valid only for LocalSnapshot")
      }
    case UploadSnapshot(snapshotId) =>
      val snapshot = cryo.inventory.snapshots(snapshotId)
      snapshot match {
        case ls: LocalSnapshot => ls.create
        case _ => println("UpdateSnapshotFileFilter is valid only for LocalSnapshot")
      }
    case msg => println("CryoActor has received an unknown message : " + msg)
  }

  def getDirectoryContent(directory: File, fileSelection: Iterable[String], fileFilters: scala.collection.Map[String, String]) = {
    //println("getDirectoryContent(%s, %s)".format(directory, fileSelection.mkString("(", ",", ")")))
    val dirContent = Option(directory.listFiles).getOrElse(Array[File]())
    dirContent.map(f => {
      val filePath = Config.baseURI.relativize(f.toURI).getPath match {
        case x if x.endsWith("/") => x.substring(0, x.length - 1)
        case x => x
      }
      //println("f=%s; af=%s".format(f, af))
      val (count, size) = ((0, 0L) /: fileSelection)((a, e) =>
        if (e.startsWith(filePath)) (a._1 + 1, a._2 + new File(Config.baseDirectory, e).length) else a)
      //val filePath = Config.baseURI.relativize(f.toURI).getPath
      //println("getDirectoryContent: filePath=%s fileFilters=%s".format(filePath, fileFilters.mkString("(", ",", ")")))
      new FileElement(f, count, size, fileFilters.get('/' + filePath))
    }).toList
  }
}

class CryoEventBus extends EventBus with SubchannelClassification {
  type Event = models.ResponseEvent
  type Classifier = String
  type Subscriber = ActorRef

  protected def classify(event: ResponseEvent) = event.path
  protected def subclassification = new Subclassification[Classifier] {
    def isEqual(x: Classifier, y: Classifier) = x == y
    def isSubclass(x: Classifier, y: Classifier) = x.startsWith(y)
  }

  protected def publish(event: ResponseEvent, subscriber: Subscriber): Unit = subscriber ! event
}