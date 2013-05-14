package org.rejna.cryo.models

import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.Source

import java.io.{ FileOutputStream, FileNotFoundException, InputStream, OutputStream }
import java.util.Date
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption._
import java.nio.channels.FileChannel

import akka.actor._

import java.util.UUID

import com.amazonaws.services.glacier.TreeHashGenerator
import com.amazonaws.services.s3.internal.InputSubstream

import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

object CryoStatus extends Enumeration {
  type CryoStatus = Value
  val Creating, Uploading, Cached, WaitingForDownload, Downloading, Remote = Value
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

  def deleteCache = Files.delete(file)

  def size: Long
}

class LocalArchive(archiveType: ArchiveType, id: String) extends Archive(archiveType, id, new DateTime, Creating) {
  import DateUtil._

  protected val remoteArchiveAttribute = attributeBuilder[Option[RemoteArchive]]("remoteArchive", None)
  def remoteArchive: Option[RemoteArchive] = remoteArchiveAttribute()
  protected def remoteArchive_= = remoteArchiveAttribute() = _

  protected val sizeAttribute = attributeBuilder("size", Files.size(file))
  def size = sizeAttribute()
  protected def size_= = sizeAttribute() = _

  val dataStream = FileChannel.open(file, CREATE_NEW)

  lazy val description = s"${archiveType}-${date.toISOString}"

  def writeBlock(block: Block) = {
    if (state != Creating) throw InvalidStateException
    dataStream.write(ByteBuffer.wrap(block.data))
    val bl = BlockLocation(block.hash, this, size, block.size)
    size += block.size
    bl
  }

  def upload: RemoteArchive = synchronized {
    if (state != Creating) throw InvalidStateException
    dataStream.close
    state = Uploading

    val rarchive = if (size > Config.multipart_threshold)
      uploadInMultiplePart
    else
      uploadInSimplePart

    Files.move(file, rarchive.file)
    remoteArchive = Some(rarchive)
    state = Cached
    rarchive
  }

  protected def uploadInSimplePart = {
    val input = new MonitoredInputStream(attributeBuilder / "transfer", s"Uploading ${description} ...", file)
    try {
      val checksum = TreeHashGenerator.calculateTreeHash(Files.newInputStream(file)) // calculateTreeHash closes input stream
      transfer = Some(input)
      val newId = Cryo.uploadArchive(input, description, checksum)
      transfer = None
      Cryo.migrate(this, newId, size, Hash(checksum))
    } finally {
      input.close
    }
  }

  protected def uploadInMultiplePart = {
    val input = new MonitoredInputStream(attributeBuilder / "transfer", s"Uploading ${description} (multipart) ...", file)
    try {
      transfer = Some(input)
      val uploadId = Cryo.initiateMultipartUpload(description)
      val binaryChecksums = ArrayBuffer[Array[Byte]]()
      for (partStart <- (0L to size by Config.partSize)) {
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
      val checksum = TreeHashGenerator.calculateTreeHash(binaryChecksums)
      val newId = Cryo.completeMultipartUpload(uploadId, size, checksum)
      Cryo.migrate(this, newId, size, Hash(checksum))

    } finally {
      input.close
    }
  }
}

class RemoteArchive(archiveType: ArchiveType, date: DateTime, id: String, val size: Long, val hash: Hash) extends Archive(archiveType, id, date, Remote) {

  try {
    if (Hash(TreeHashGenerator.calculateTreeHash(Files.newInputStream(file))) != hash)
      Files.delete(file)
    else
      state = Cached
  } catch {
    case _: FileNotFoundException =>
  }

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
        val output = new MonitoredOutputStream(attributeBuilder, s"Downloading archive ${id}",
          Files.newOutputStream(file, CREATE_NEW),
          input.available)
        try {
          transfer = Some(output)
          StreamOps.copyStream(input, output)
          state = Cached
        } finally {
          input.close
          output.close
        }
      })
    }
  }
}
