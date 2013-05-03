package org.rejna.cryo.models

import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.Source
import scalax.io._

import java.io.{ File, FileOutputStream }
import java.util.Date

import akka.actor.Cancellable

import akka.actor._

import java.util.UUID

import com.amazonaws.services.glacier.TreeHashGenerator
import com.amazonaws.services.s3.internal.InputSubstream

import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

object CryoStatus extends Enumeration {
  type CryoStatus = Value
  val Creating, Cached, WaitingForDownload, Downloading, Remote = Value
}

object ArchiveType extends Enumeration {
  type ArchiveType = Value
  val Data, Index = Value
}

import ArchiveType._
import CryoStatus._

abstract class Archive(val archiveType: ArchiveType, val id: String, val date: DateTime, initState: CryoStatus) {
  val attributeBuilder = Cryo.attributeBuilder / archiveType.toString / id

  protected val stateAttribute = attributeBuilder("state", initState)
  def state: CryoStatus = stateAttribute()
  protected def state_= = stateAttribute() = _
  def onStateChange = stateAttribute.onChange _

  //protected val transferAttribute = attributeBuilder[Option[Transfert]]("transfert", None)
  //def transfer = transferAttribute()
  //protected def transfer_= = transferAttribute() = _
  var transfer: Option[Transfer] = None

  val file = Config.getFile(archiveType, id)

  def deleteCache = file.delete
  
  def size: Long
}

class LocalArchive(archiveType: ArchiveType, id: String) extends Archive(archiveType, id, new DateTime, Creating) {
  
  if (!file.exists) file.createNewFile

  protected val remoteArchiveAttribute = attributeBuilder[Option[RemoteArchive]]("remoteArchive", None)
  def remoteArchive: Option[RemoteArchive] = remoteArchiveAttribute()
  protected def remoteArchive_= = remoteArchiveAttribute() = _

  protected val sizeAttribute = attributeBuilder("size", file.length)
  def size = sizeAttribute()
  protected def size_= = sizeAttribute() = _

  val dataStream: Output = Resource.fromFile(file)

  lazy val description = "%s-%s".format(archiveType.toString, ISODateTimeFormat.dateTimeNoMillis().print(date))

  def writeBlock(block: Block) = {
    if (state != Creating) throw InvalidStateException
    dataStream.write(block.data)
    val bl = BlockLocation(block.hash, this, size, block.size)
    size += block.size
    bl
  }

  def upload: RemoteArchive = synchronized {
    if (state != Creating) throw InvalidStateException

    val rarchive = if (size > Config.multipart_threshold)
      uploadInMultiplePart
    else
      uploadInSimplePart

    state = Cached
    remoteArchive = Some(rarchive)
    file.renameTo(rarchive.file)
    rarchive
  }

  protected def uploadInSimplePart = {
    val input = new MonitoredInputStream(attributeBuilder.subBuilder("transfer"), "Uploading %s ...".format(description), file)
    transfer = Some(input)
    val checksum = TreeHashGenerator.calculateTreeHash(file)
    val newId = Cryo.uploadArchive(input, description, checksum)
    transfer = None
    input.close
    Cryo.migrate(this, newId, size, Hash(checksum))
  }

  protected def uploadInMultiplePart = {
    val input = new MonitoredInputStream(attributeBuilder.subBuilder("transfer"), "Uploading %s (multipart) ...".format(description), file)
    transfer = Some(input)
    val uploadId = Cryo.initiateMultipartUpload(description)
    val binaryChecksums = ArrayBuffer[Array[Byte]]()
    (0L to size by Config.partSize).foreach { partStart =>
      val length = (size - partStart).min(Config.partSize)
      val subInput = new InputSubstream(input, partStart, length, false)

      subInput.mark(-1)
      val checksum = TreeHashGenerator.calculateTreeHash(subInput)
      binaryChecksums += Array.range(0, checksum.size, 2).map { i =>
        java.lang.Integer.parseInt(checksum.slice(i, i + 2).mkString, 16).toByte
      }
      subInput.reset

      Cryo.uploadMultipartPart(uploadId, subInput, "bytes " + partStart + "-" + (partStart + length - 1) + "/*", checksum)
    }
    transfer = None
    input.close

    val checksum = TreeHashGenerator.calculateTreeHash(binaryChecksums)
    val newId = Cryo.completeMultipartUpload(uploadId, size, checksum)
    Cryo.migrate(this, newId, size, Hash(checksum))
  }
}

class RemoteArchive(archiveType: ArchiveType, date: DateTime, id: String, val size: Long, val hash: Hash) extends Archive(archiveType, id, date, Remote) {

  if (!file.exists || file.length == 0 || Hash(TreeHashGenerator.calculateTreeHash(file)) != hash)
    file.delete
  else
    state = Cached

  def forceDownload = {
    state = Remote
    download
  }

  def download = {
    if (state != Remote) {
      state = WaitingForDownload

      /*
	  val out = new FileOutputStream(file)
      val job = new Job("JobId", "Downloading archive " + id, out)
      job.totalBytes = 100
      var xfer: Option[Cancellable] = None
      xfer = Some(Akka.system.scheduler.schedule(1 seconds, 2 seconds) {
        println("writing 10 bytes in file")
        if (job.transferredBytes < 100)
          job.write(Array.ofDim[Byte](10))
        else
          xfer.foreach {
            state = Cached
            job.close
            _.cancel
          }
      })
      job
      * DEBUG */
      val jobId = Cryo.initiateDownload(id, jobId => {
        state = Downloading
        val input = Cryo.getJobOutput(jobId)
        val output = new MonitoredOutputStream(attributeBuilder, "Downloading archive %s".format(id),
          new FileOutputStream(file),
          input.available)
        transfer = Some(output)
        Resource.fromInputStream(input) copyDataTo Resource.fromOutputStream(output)
        state = Cached
      })
    }
  }
}