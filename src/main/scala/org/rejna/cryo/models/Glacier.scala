package org.rejna.cryo.models

import scala.concurrent.{ Lock, SyncVar }
import scala.collection.mutable.{ ArrayBuffer, HashMap }
import scala.collection.JavaConversions._

//import scalax.io._
//import scalax.io.Resource._

import java.io.{ File, BufferedInputStream, OutputStream, BufferedOutputStream, FileOutputStream, ByteArrayOutputStream, InputStream }
import java.util.Date

import akka.actor._
//import play.Logger
//import play.api.Play.current
//import play.api.libs.concurrent._
//import play.api.libs.iteratee._

import com.amazonaws.auth.policy.{ Policy, Principal, Statement, Resource }
import com.amazonaws.auth.policy.Statement.Effect
import com.amazonaws.auth.policy.actions.SQSActions
import com.amazonaws.services.glacier.{ AmazonGlacier, AmazonGlacierClient, TreeHashGenerator }
import com.amazonaws.services.glacier.model._
import com.amazonaws.services.s3.internal.InputSubstream
import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sqs.model.{ CreateQueueRequest, GetQueueAttributesRequest, SetQueueAttributesRequest, ReceiveMessageRequest }
import com.amazonaws.services.sns.AmazonSNSClient
import com.amazonaws.services.sns.model.{ CreateTopicRequest, SubscribeRequest }
import com.amazonaws.{ AmazonWebServiceRequest, ResponseMetadata, ClientConfiguration }
import com.amazonaws.auth.AWSCredentials

/*
object OldCryo {
  val glacier = new AmazonGlacierClient(Config.awsCredentials);
  glacier.setEndpoint("https://glacier." + Config.region + ".amazonaws.com/")
  val sqs = new AmazonSQSClient(Config.awsCredentials)
  sqs.setEndpoint("https://sqs." + Config.region + ".amazonaws.com")
  lazy val sqsQueueURL = createSqsQueue
  lazy val sqsQueueARN = getSqsArn(sqsQueueURL)
  lazy val snsTopicARN = createNotification(sqsQueueARN)
  //val jobs = new HashMap[String, Job]()
  
  
  val actor = Akka.system.actorOf(Props[CryoActor], name="cryo")
  //private val _snapshots = ArrayBuffer[RemoteSnapshot]() // TODO fill the collection
  private val _blocks = HashMap[Hash, BlockLocation]()

  def getBlockLocation(hash: Hash): Option[BlockLocation] = _blocks.get(hash)

  private def createSqsQueue: String = {
    sqs.createQueue(new CreateQueueRequest().withQueueName(Config.sqsQueueName)).getQueueUrl
  }

  private def getSqsArn(sqsQueueURL: String): String = {
    import scala.collection.JavaConversions._
    val sqsQueueARN = sqs.getQueueAttributes(
      new GetQueueAttributesRequest()
        .withQueueUrl(sqsQueueURL)
        .withAttributeNames("QueueArn"))
      .getAttributes.get("QueueArn")

    val sqsPolicy = new Policy().withStatements(
      new Statement(Effect.Allow)
        .withPrincipals(Principal.AllUsers)
        .withActions(SQSActions.SendMessage)
        .withResources(new Resource(sqsQueueARN)))
    sqs.setQueueAttributes(new SetQueueAttributesRequest(sqsQueueURL, Map("Policy" -> sqsPolicy.toJson)))

    sqsQueueARN
  }

  private def createNotification(sqsQueueARN: String) = {
    val sns = new AmazonSNSClient(Config.awsCredentials)
    sns.setEndpoint("https://sns." + Config.region + ".amazonaws.com")

    val snsTopicARN = sns.createTopic(new CreateTopicRequest()
      .withName(Config.snsTopicName)).getTopicArn

    sns.subscribe(new SubscribeRequest()
      .withTopicArn(snsTopicARN)
      .withEndpoint(sqsQueueARN)
      .withProtocol("sqs")).getSubscriptionArn
    snsTopicARN
  }

  private def handleMessage = {
    while (true) {
      val messages = sqs.receiveMessage(new ReceiveMessageRequest(sqsQueueURL).withMaxNumberOfMessages(10)).getMessages
      for (m <- messages) {
        val message = JobStatusMessage(m.getBody)
        jobs.get(message.jobId).map(_.receive(message))
      }
    }
  }

  def upload(archive: LocalArchive): UploadProcess = {
    val process = if (archive.file.length > Config.multipart_threshold)
      uploadInMultiplePart(archive)
    else
      uploadInSimplePart(archive)
    process.getPromiseArchive.onRedeem { remoteArchive =>
      archive.uploadComplete(remoteArchive)
    }
    process

  }

  protected def uploadInSimplePart(archive: LocalArchive) = {
    val input = new UploadProcess("Uploading %s ...".format(archive.description), archive.file)
    input.setPromiseArchive(Akka.future {
      val checksum = TreeHashGenerator.calculateTreeHash(archive.file)
      val aid = glacier.uploadArchive(new UploadArchiveRequest()
        .withArchiveDescription(archive.description)
        .withVaultName(Config.vaultName)
        .withChecksum(checksum)
        .withBody(input)
        .withContentLength(archive.file.length)).getArchiveId
      input.close
      new RemoteArchive(archive.archiveType, archive.date, aid, archive.file.length, Hash(checksum))
    })
    input
  }

  protected def uploadInMultiplePart(archive: LocalArchive) = {
    val input = new UploadProcess("Uploading %s (multipart) ...".format(archive.description), archive.file)
    input.setPromiseArchive(Akka.future {
      val uploadId = glacier.initiateMultipartUpload(new InitiateMultipartUploadRequest()
        .withArchiveDescription(archive.description)
        .withVaultName(Config.vaultName)
        .withPartSize(Config.partSize.toString)).getUploadId

      val binaryChecksums = ArrayBuffer[Array[Byte]]()
      val fileLength = archive.file.length
      (0L to fileLength by Config.partSize).foreach { partStart =>
        val length = (fileLength - partStart).min(Config.partSize)
        val subInput = new InputSubstream(input, partStart, length, false)

        subInput.mark(-1)
        val checksum = TreeHashGenerator.calculateTreeHash(subInput)
        binaryChecksums += Array.range(0, checksum.size, 2).map { i => java.lang.Integer.parseInt(checksum.slice(i, i + 2).mkString, 16).toByte }
        subInput.reset

        glacier.uploadMultipartPart(new UploadMultipartPartRequest()
          .withChecksum(checksum)
          .withBody(subInput)
          .withRange("bytes " + partStart + "-" + (partStart + length - 1) + "/ *")
          .withUploadId(uploadId)
          .withVaultName(Config.vaultName))
      }
      input.close

      val checksum = TreeHashGenerator.calculateTreeHash(binaryChecksums)
      val aid = glacier.completeMultipartUpload(new CompleteMultipartUploadRequest()
        .withArchiveSize(fileLength.toString)
        .withVaultName(Config.vaultName)
        .withChecksum(checksum)
        .withUploadId(uploadId)).getArchiveId
      new RemoteArchive(archive.archiveType, archive.date, aid, archive.file.length, Hash(checksum))
    })
    input
  }

  def downloadArchive(archiveId: String, file: File): Job = {
    val jobId = glacier.initiateJob(
      new InitiateJobRequest()
        .withVaultName(Config.vaultName)
        .withJobParameters(
          new JobParameters()
            .withArchiveId(archiveId)
            .withType("archive-retrieval")
            .withSNSTopic(snsTopicARN))).getJobId

    val job = new Job(jobId, "Download archive %s".format(archiveId), new FileOutputStream(file))
    jobs += jobId -> job
    job
  }

  def updateInventory: Job = {
    val file = File.createTempFile("inventory_", ".glacier")
    val jobId = glacier.initiateJob(
      new InitiateJobRequest()
        .withVaultName(Config.vaultName)
        .withJobParameters(
          new JobParameters()
            .withType("inventory-retrieval")
            .withSNSTopic(snsTopicARN))).getJobId

    val job = new Job(jobId, "Download vault inventory", new FileOutputStream(file))
    *
    registerJobHandler(job, new JobHandler {
      override def onSuccess(message: AWSJobStatus) = {
        if (Config.inventoryFile.exists)
          Config.inventoryFile.renameTo(File.createTempFile("old_inventory_", ".glacier"))
        file.renameTo(Config.inventoryFile)
        val newInventory = new Inventory
        _inventory.updated(newInventory)
        _inventory = newInventory
      }
    })
    * 
    
    job
  }

  def describeJob(jobId: String): Promise[JobStatusMessage] = Akka.future { // FIXME DescribeJobResult <=> AWSJobStatus
    JobStatusMessage(glacier.describeJob(new DescribeJobRequest(Config.vaultName, jobId)))
  }

  def getJobStream(job: Job) = Akka.future {
    glacier.getJobOutput(new GetJobOutputRequest()
      .withVaultName(Config.vaultName)
      .withJobId(job.jobId)).getBody
  }
}

*
class JobHandler {
  def apply(message: JobStatusMessage) = {
    message.statusCode match {
      case "Succeeded" => onSuccess(message)
      case "InProgress" => inProgress(message)
      case "Failed" => onFail(message)
    }
  }
  def onSuccess(message: JobStatusMessage) = {}
  def inProgress(message: JobStatusMessage) = {}
  def onFail(message: JobStatusMessage) = {}

}

class CryoActor extends Actor {
  val channels = ArrayBuffer[PushEnumerator[ResponseEvent]]()

  def receive = {
    case Connect =>
      println(">connect")
      val channel = Enumerator.imperative[ResponseEvent]()
      channels += channel
      sender ! Connected(channel)

    case e: InventoryRequest =>
      println(">inventory")
      e.responseChannel.getOrElse(sys.error("Response channel is not set")).push(UpdateSnapshotList(Inventory.snapshots.values.toList))

    case e: DownloadRequest =>
      val job = Inventory.archives(e.id).download
      job.addListener(new TransferListener {
        def transferred(current: Long, total: Long) = {
          val p = ProgressResponse(e.id, job.title, job.label,
          if (current == 0) 0
          else (current * 100 / total).toInt)
          println("sending %s".format(p))
          e.responseChannel.getOrElse(sys.error("Response channel is not set")).push(p)
        }
      })
    case e: ProgressRequest =>
      println("ProgressRequest")
      Inventory.archives(e.id).currentJob().map { job =>
        println("OK !")
        job.addListener(new TransferListener {
          override def transferred(current: Long, total: Long) = {
            println("ProgressRequest.transferred")
            val p = ProgressResponse(e.id, job.title, job.label, (current * 100 / total).toInt)
            println("sending %s".format(p))
            e.responseChannel.getOrElse(sys.error("Response channel is not set")).push(p)
          }
        })
      }
    case e =>
      println(">>" + e)

    //Cryo.
    // TODO  notifyClients(InventoryResult(Json.toJson(Seq(1, 2, 3))))
  }

  def notifyClients(event: ResponseEvent) = channels.foreach(_.push(event))
}
* 
*/

