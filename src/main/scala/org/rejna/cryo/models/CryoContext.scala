package org.rejna.cryo.models

import scala.util.{ Success, Failure, Random }
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }
import scala.language.postfixOps

import akka.actor.{ ActorSystem, Props, PoisonPill }
import akka.pattern.gracefulStop
import akka.util.Timeout

import java.nio.file.{ Path, FileSystems }
import javax.crypto.{ Cipher, KeyGenerator, CipherOutputStream }
import javax.crypto.spec.IvParameterSpec

import com.typesafe.config.Config

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.{ ClientConfiguration, Protocol }

class CryoContext(val system: ActorSystem, val config: Config) {
  import org.rejna.util.IsoUnit._
  implicit val executionContext = system.dispatcher

  val exitPromise = Promise[Any]()
  var shutdownHooks = exitPromise.future
  def addShutdownHook[T](f: => T) {
    shutdownHooks = shutdownHooks.map((Unit) => f)
  }

  def addFutureShutdownHook[T](f: => Future[T]) {
    shutdownHooks = shutdownHooks.flatMap((Unit) => f)
  }

  def shutdown() {
    exitPromise.success()
    addShutdownHook { system.shutdown }
    addShutdownHook { System.exit(0) }
    shutdownHooks.onComplete {
      case Success(_) =>
        println("Shutdown completed")
        System.exit(0)
      case Failure(e) =>
        e.printStackTrace()
        System.exit(0)
    }
  }

  val hashAlgorithm = config.getString("cryo.hash-algorithm") // TODO use MessageDigest.getInstance
  val bufferSize = config.getBytes("cryo.buffer-size").toInt

  val multipartThreshold = config.getBytes("cryo.multipart-threshold")
  val partSize = config.getBytes("cryo.part-size")
  val archiveSize = config.getBytes("cryo.archive-size")

  val workingDirectory = FileSystems.getDefault.getPath(config.getString("cryo.working-directory"))
  val baseDirectory = FileSystems.getDefault.getPath(config.getString("cryo.base-directory"))

  def blockSizeFor(fileSize: Long) = {
    if (fileSize < (1 mebi)) 1 kibi
    else if (fileSize < (512 mebi)) 512 kibi
    else if (fileSize < (1 gibi)) 1 mebi
    else if (fileSize < (512 gibi)) 512 mebi
    else 1 gibi
  }

  def getTimeout(clazz: Class[_]) = Timeout(30 seconds)

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
  addFutureShutdownHook { gracefulStop(notification, 10 seconds) }
  
  val deadLetterMonitor = system.actorOf(Props(classOf[DeadLetterMonitor], this), "deadletter")
  addFutureShutdownHook { gracefulStop(deadLetterMonitor, 10 seconds) }

  val cryo = system.actorOf(Props(classOf[Glacier], this), "cryo")
  addFutureShutdownHook { gracefulStop(cryo, 10 seconds) }

  val manager = system.actorOf(Props(classOf[Manager], this), "manager")
  addFutureShutdownHook { gracefulStop(manager, 10 seconds) }

  val hashcatalog = system.actorOf(Props(classOf[HashCatalog], this), "catalog")
  addFutureShutdownHook { gracefulStop(hashcatalog, 10 seconds) }

  val inventory = system.actorOf(Props(classOf[Inventory], this), "inventory")
  addFutureShutdownHook { gracefulStop(inventory, 10 seconds) }

  val datastore = system.actorOf(Props(classOf[Datastore], this), "datastore")
  addFutureShutdownHook { gracefulStop(datastore, 10 seconds) }

}