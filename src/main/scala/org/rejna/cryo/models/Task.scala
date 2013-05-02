package org.rejna.cryo.models

import scala.collection.mutable.ArrayBuffer

import java.io.{ File, InputStream, OutputStream }
import java.util.concurrent.TimeUnit

import com.amazonaws.services.glacier.model.{ DescribeJobRequest, DescribeJobResult }
import com.amazonaws.services.glacier.TreeHashGenerator
import org.rejna.util.IsoUnit

trait ProgressStatus {
  def title: String
  def progress: Int
  def label: String
}

trait Transfer extends ProgressStatus {
  import CryoJson._
  val attributeBuilder: AttributeBuilder

  val transferredBytesAttribute = attributeBuilder("transferredBytes", 0L)
  def transferredBytes = transferredBytesAttribute()
  def transferredBytes_= = transferredBytesAttribute() = _

  val totalBytesAttribute = attributeBuilder("totalBytes", 0L)
  def totalBytes = totalBytesAttribute()
  def totalBytes_= = totalBytesAttribute() = _

  val progressAttribute = attributeBuilder("progress", () => {
    if (totalBytes > 0)
      (100 * transferredBytes / totalBytes).toLong
    else
      0L
  })
  progressAttribute <* totalBytesAttribute <* transferredBytesAttribute
  def progress = progressAttribute().toInt

  def labelAttribute = attributeBuilder("label", () => IsoUnit(transferredBytes) + "B/" + IsoUnit(totalBytes) + "B")
  labelAttribute <* totalBytesAttribute <* transferredBytesAttribute
  def label = labelAttribute()
}

/*
class UploadProcess(description: String, file: File) extends MonitoredInputStream(description, file) {
  var _archive: Option[Promise[RemoteArchive]] = None
  
  //lazy val hash = Hash(TreeHashGenerator.calculateTreeHash(file))
  
  lazy val size = file.length
  
  def setPromiseArchive(promiseArchive: Promise[RemoteArchive]) = _archive = Some(promiseArchive)
  
  def setArchive(archive: RemoteArchive) = _archive = Some(Promise.pure(archive))
  
  def getPromiseArchive = _archive getOrElse { throw new InvalidStateException }
  
  def getArchive = getPromiseArchive.await.get
}
*/

//class Job(val jobId: String, title: String, out: OutputStream) extends MonitoredOutputStream(title, out, 0) {
//  var _status: Option[JobStatusMessage] = None
//  private val success = ArrayBuffer[(JobStatusMessage, InputStream) => Unit]()
//  
//  /*
//  def status: Promise[JobStatusMessage] = Akka.future {
//    _status.getOrElse {
//      Cryo.describeJob(jobId).await.get
//    }
//  }
//  */
//  
//  def receive(message: JobStatusMessage) = {
//    
//  }
//  
//  def onSuccess(callback: (JobStatusMessage, InputStream) => Unit) = success += callback
//
//  /*
//  def update = {
//    _status = None
//    status
//  }
//  */
//}