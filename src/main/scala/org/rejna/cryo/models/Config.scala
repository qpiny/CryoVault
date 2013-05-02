package org.rejna.cryo.models

import scala.util.Random
import java.io.File
import javax.crypto.{ Cipher, KeyGenerator, CipherOutputStream }
import javax.crypto.spec.IvParameterSpec
import com.amazonaws.auth.BasicAWSCredentials
import org.apache.commons.io.filefilter._

import ArchiveType._

object Config {
  import org.rejna.util.IsoUnit._
  val hashAlgorithm = "SHA"
  val bufferSize: Int = 1 mebi

  val multipart_threshold = 10 mebi

  def blockSizeFor(fileSize: Long) = {
    if (fileSize < (1 mebi)) 1 kibi
    else if (fileSize < (512 mebi)) 512 kibi
    else if (fileSize < (1 gibi)) 1 mebi
    else if (fileSize < (512 gibi)) 512 mebi
    else 1 gibi
  }

  val awsCredentials = new BasicAWSCredentials("AKIAIR3XIFPGVNFBDPHA", "oXmmFkjZczHPTJ2bgli4EoKcoDvc9pvw3EkwndPc")
  val region = "eu-west-1" //"us-east-1"
  val sqsQueueName = "GlacierNotificationQueue"
  val snsTopicName = "GlacierNotificationTopic"
  val sqsQueueARN = "arn:aws:sqs:eu-west-1:235715319590:GlacierNotificationQueue"
  val sqsTopicARN = "arn:aws:sns:eu-west-1:235715319590:GlacierNotificationTopic"

  val vaultName = "cryo"

  val partSize = 1 mebi

  val archiveSize = 50 mebi

  val fileFilter = new PrefixFileFilter("bk_")
  val fileLocation = "/tmp"

  val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
  val key = {
    val kg = KeyGenerator.getInstance("AES"); // TODO
    kg.init(128)
    kg.generateKey()
  }
  val iv = Array.ofDim[Byte](16)
  Random.nextBytes(iv)
  val paramSpec = new IvParameterSpec(iv)
  cipher.init(Cipher.ENCRYPT_MODE, key, paramSpec);

  val cacheDirectory = new File("/tmp")
  val inventoryFile = new File(cacheDirectory, "inventory.glacier")

  val baseDirectory = new File("/")
  val baseURI = baseDirectory.toURI

  def getFile(archiveType: ArchiveType, id: String) = new File(Config.cacheDirectory.getAbsolutePath + File.separator + archiveType + "_" + id + ".glacier")

  /*
  def archiveToFile(archiveId: String): File = {
    if (archiveId != null && archiveId != "")
      new File(cacheDirectory.getAbsolutePath + File.separator + "archive_" + archiveId + ".glacier")
    else
      File.createTempFile("local_", ".glacier", cacheDirectory)
  }
  */
}
