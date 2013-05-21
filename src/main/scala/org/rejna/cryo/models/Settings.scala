package org.rejna.cryo.models

import scala.util.Random
import scala.concurrent.duration._
import scala.language.postfixOps

import java.nio.file.{ Path, FileSystems }
import javax.crypto.{ Cipher, KeyGenerator, CipherOutputStream }
import javax.crypto.spec.IvParameterSpec

import com.typesafe.config.ConfigFactory

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.{ ClientConfiguration, Protocol }

import ArchiveType._

class Settings {
  import org.rejna.util.IsoUnit._
  val config = ConfigFactory.defaultOverrides.withFallback(ConfigFactory.load)
  val hashAlgorithm = config.getString("cryo.hash-algorithm")
  val bufferSize = config.getBytes("cryo.buffer-size").toInt

  val multipartThreshold = config.getBytes("cryo.multipart-threshold")
  val partSize = config.getBytes("cryo.part-size")
  val archiveSize = config.getBytes("cryo.archive-size")

  val workingDirectory = FileSystems.getDefault.getPath(config.getString("cryo.working-directory"))
  val inventoryFile = FileSystems.getDefault.getPath(config.getString("cryo.inventory-file"))
  val baseDirectory = FileSystems.getDefault.getPath(config.getString("cryo.base-directory"))

  def getFile(archiveType: ArchiveType, id: String) =
    workingDirectory.resolve(archiveType + "_" + id + ".glacier")

  def blockSizeFor(fileSize: Long) = {
    if (fileSize < (1 mebi)) 1 kibi
    else if (fileSize < (512 mebi)) 512 kibi
    else if (fileSize < (1 gibi)) 1 mebi
    else if (fileSize < (512 gibi)) 512 mebi
    else 1 gibi
  }
  
  val queueRequestInterval = config.getMilliseconds("cryo.queue-request-interval").toInt milliseconds

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
  val sqsTopicARN = config.getString("glacier.sqs-topic-arn")
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
}
