package org.rejna.cryo.models

import scala.util.Random
import scala.language.postfixOps

import java.nio.file.{ Path, FileSystems }
import javax.crypto.{ Cipher, KeyGenerator, CipherOutputStream }
import javax.crypto.spec.IvParameterSpec

import com.typesafe.config.ConfigFactory

import com.amazonaws.auth.BasicAWSCredentials

import ArchiveType._

object Config {
  import org.rejna.util.IsoUnit._
  val config = ConfigFactory.load
  val hashAlgorithm = config.getString("hash-algorithm")
  val bufferSize = config.getBytes("buffer-size")

  val multipartThreshold = config.getBytes("multipart-threshold") 

  def blockSizeFor(fileSize: Long) = {
    if (fileSize < (1 mebi)) 1 kibi
    else if (fileSize < (512 mebi)) 512 kibi
    else if (fileSize < (1 gibi)) 1 mebi
    else if (fileSize < (512 gibi)) 512 mebi
    else 1 gibi
  }

  val awsCredentials = new BasicAWSCredentials(
      config.getString("glacier.access-key"),
      config.getString("glacier.secret-key"))
  val region = config.getString("region")
  val sqsQueueName = config.getString("glacier.sqs-queue-name")
  val snsTopicName = config.getString("glacier.sns-topic-name")
  val sqsQueueARN = config.getString("glacier.sqs-queue-arn")
  val sqsTopicARN = config.getString("glacier.sqs-topic-arn")

  val vaultName = config.getString("vault-name")

  val partSize = config.getBytes("part-size")

  val archiveSize = config.getBytes("archive-size")

  val cipher = Cipher.getInstance(config.getString("cipher"));
  val key = {
    val kg = KeyGenerator.getInstance("AES"); // TODO
    kg.init(128)
    kg.generateKey()
  }
  val iv = Array.ofDim[Byte](16)
  Random.nextBytes(iv)
  val paramSpec = new IvParameterSpec(iv)
  cipher.init(Cipher.ENCRYPT_MODE, key, paramSpec);

  val workingDirectory = FileSystems.getDefault.getPath(config.getString("working-directory"))
  val inventoryFile = FileSystems.getDefault.getPath(config.getString("inventory-file"))
  val baseDirectory = FileSystems.getDefault.getPath(config.getString("base-directory"))

  def getFile(archiveType: ArchiveType, id: String) =
   workingDirectory.resolve(archiveType + "_" + id + ".glacier")
}
