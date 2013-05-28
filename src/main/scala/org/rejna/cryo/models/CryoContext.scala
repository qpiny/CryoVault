package org.rejna.cryo.models

import scala.util.Random
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{ ActorRefFactory, Props }

import java.nio.file.{ Path, FileSystems }
import javax.crypto.{ Cipher, KeyGenerator, CipherOutputStream }
import javax.crypto.spec.IvParameterSpec

import com.typesafe.config.Config

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.{ ClientConfiguration, Protocol }

class CryoContext(val system: ActorRefFactory, val config: Config) {
  import org.rejna.util.IsoUnit._

  val hashAlgorithm = config.getString("cryo.hash-algorithm") // TODO use MessageDigest.getInstance
  val bufferSize = config.getBytes("cryo.buffer-size").toInt

  val multipartThreshold = config.getBytes("cryo.multipart-threshold")
  val partSize = config.getBytes("cryo.part-size")
  val archiveSize = config.getBytes("cryo.archive-size")

  val workingDirectory = FileSystems.getDefault.getPath(config.getString("cryo.working-directory"))
  val inventoryFile = FileSystems.getDefault.getPath(config.getString("cryo.inventory-file"))
  val baseDirectory = FileSystems.getDefault.getPath(config.getString("cryo.base-directory"))

  def blockSizeFor(fileSize: Long) = {
    if (fileSize < (1 mebi)) 1 kibi
    else if (fileSize < (512 mebi)) 512 kibi
    else if (fileSize < (1 gibi)) 1 mebi
    else if (fileSize < (512 gibi)) 512 mebi
    else 1 gibi
  }

  val cipher = Cipher.getInstance(config.getString("cryo.cipher"));
  val key = {
    val kg = KeyGenerator.getInstance("AES"); // TODO
    kg.init(128)
    kg.generateKey()
  }
  val iv = Array.ofDim[Byte](16)
  Random.nextBytes(iv)
  val paramSpec = new IvParameterSpec(iv)
  cipher.init(Cipher.ENCRYPT_MODE, key, paramSpec);

  val awsCredentials = new BasicAWSCredentials(
    config.getString("glacier.access-key"),
    config.getString("glacier.secret-key"))
  val region = config.getString("glacier.region")
  val sqsQueueName = config.getString("glacier.sqs-queue-name")
  val snsTopicName = config.getString("glacier.sns-topic-name")
  val sqsQueueARN = config.getString("glacier.sqs-queue-arn")
  val snsTopicARN = config.getString("glacier.sns-topic-arn")
  val queueRequestInterval = config.getMilliseconds("cryo.queue-request-interval").toInt milliseconds
  val vaultName = config.getString("glacier.vault-name")

  val awsConfig = (new ClientConfiguration)
    .withConnectionTimeout(config.getInt("aws.connection-timeout"))
    .withMaxConnections(config.getInt("aws.max-connections"))
    .withMaxErrorRetry(config.getInt("aws.max-error-retry"))
    .withProtocol(Protocol.valueOf(config.getString("aws.protocol")))
    .withProxyDomain(try { config.getString("aws.proxy-domain") } catch { case _: Throwable => null })
    .withProxyHost(try { config.getString("aws.proxy-host") } catch { case _: Throwable => null })
    .withProxyPassword(try { config.getString("aws.proxy-password") } catch { case _: Throwable => null })
    .withProxyPort(config.getInt("aws.proxy-port"))
    .withProxyUsername(try { config.getString("aws.proxy-username") } catch { case _: Throwable => null })
    .withProxyWorkstation(try { config.getString("aws.proxy-workstation") } catch { case _: Throwable => null })
    .withSocketBufferSizeHints(
      config.getInt("aws.socket-send-buffer-size-hint"), config.getInt("aws.socket-receive-buffer-size-hint"))
    .withSocketTimeout(config.getInt("aws.socket-timeout"))
    .withUserAgent(config.getString("aws.user-agent"))

  if (config.getBoolean("aws.disable-cert-checking"))
    System.setProperty("com.amazonaws.sdk.disableCertChecking", "true")

  val notification = system.actorOf(Props(classOf[QueueNotification], this), "notification")
  val cryo = system.actorOf(Props(classOf[Glacier], this), "cryo")
  val datastore = system.actorOf(Props(classOf[DataStore], this), "datastore")
  val inventory = system.actorOf(Props(classOf[Inventory], this), "inventory")

  val manager = system.actorOf(Props(classOf[Manager], this), "manager")
  val hashcatalog = system.actorOf(Props(classOf[HashCatalog], this), "catalog")
}