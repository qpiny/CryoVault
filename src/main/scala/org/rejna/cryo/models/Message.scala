package org.rejna.cryo.models

import com.amazonaws.services.glacier.model.DescribeJobResult

import java.util.Date
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

import net.liftweb.json._

/* Communication between Glacier and Cryo */


//JField("ArchiveDescription", JString(description)) ::

//  implicit def inventoryRead(json: JsValue) = {
//    val description = (json \ "ArchiveDescription").as[String]
//    val Array(t, d) = description.split("-", 2)
//    var archiveType = ArchiveType.withName(t)
//
//    new InventoryMessage(
//      (json \ "InventoryDate").as[DateTime],
//      (json \\ "ArchiveList").map(a => new RemoteArchive(
//        archiveType,
//        isoToDate(d),
//        (json \ "ArchiveId").as[String],
//        (json \ "Size").as[Long],
//        Hash((json \ "SHA256TreeHash").as[String]))).toList)
//  }
//}
/*
case class JobStatusMessage(
  action: String,
  archiveId: Option[String],
  archiveSize: Option[Long],
  completed: Boolean,
  completionDate: Option[DateTime],
  creationDate: DateTime,
  inventorySize: Option[Long],
  jobDescription: String,
  jobId: String,
  hash: Option[Hash],
  snsTopic: String,
  statusCode: String,
  statusMessage: String,
  vaultArn: String)

object JobStatusMessage extends Message {
  def apply(str: String) = AJSReads.reads(Json.parse(str))
  def apply(djr: DescribeJobResult): JobStatusMessage = {
    JobStatusMessage(
      djr.getAction,
      Option(djr.getArchiveId),
      Option(djr.getArchiveSizeInBytes),
      djr.getCompleted,
      Option(djr.getCompletionDate).map(d => isoToDate(d)),
      isoToDate(djr.getCreationDate),
      Option(djr.getInventorySizeInBytes),
      djr.getJobDescription,
      djr.getJobId,
      Option(djr.getSHA256TreeHash).map(Hash(_)),
      djr.getSNSTopic,
      djr.getStatusCode,
      djr.getStatusMessage,
      djr.getVaultARN)
  }

  implicit object AJSReads extends Reads[JobStatusMessage] {
    def reads(json: JsValue): JobStatusMessage = JobStatusMessage(
      (json \ "Action").as[String],
      (json \ "ArchiveId").asOpt[String],
      (json \ "ArchiveSizeInBytes").asOpt[Long],
      (json \ "Completed").as[Boolean],
      (json \ "CompletionDate").asOpt[DateTime], //2012-05-15T17:21:39.33,
      (json \ "CreationDate").as[DateTime],
      (json \ "InventorySizeInBytes").asOpt[Long],
      (json \ "JobDescription").as[String],
      (json \ "JobId").as[String],
      (json \ "SHA256TreeHash").asOpt[String].map(Hash(_)),
      (json \ "SNSTopic").as[String],
      (json \ "StatusCode").as[String],
      (json \ "StatusMessage").as[String],
      (json \ "VaultARN").as[String])
  }
}
*/
case class InventoryMessage(val date: DateTime, val archives: List[RemoteArchive])
object InventoryMessage {
  implicit val format = new DefaultFormats {
    override val dateFormat = new DateFormat {
      def parse(s: String): Option[Date] =
        try {
          Some(ISODateTimeFormat.dateTimeNoMillis.parseDateTime(s).toDate)
        } catch {
          case _: IllegalArgumentException => None
        }
      def format(d: Date): String = ISODateTimeFormat.dateTimeNoMillis().print(new DateTime(d))
    }
  }
  def apply(msg: String): InventoryMessage = apply(parse(msg))
  def apply(json: JValue) = json.extract[InventoryMessage]
}
//  def apply(json: JsValue)(implicit cryo: Cryo) = {
//    val description = (json \ "ArchiveDescription").as[String]
//    val Array(t, d) = description.split("-", 2)
//
//    new InventoryMessage(
//      (json \ "InventoryDate").as[DateTime](Message.DateReads),
//      (json \\ "ArchiveList").map(a => new RemoteArchive(
//        ArchiveType.withName(t),
//        Message.isoToDate(d),
//        (json \ "ArchiveId").as[String],
//        (json \ "Size").as[Long],
//        Hash((json \ "SHA256TreeHash").as[String]))).toList)
//  }
//  
//}

//object InventoryMessage extends Message {
//  def apply(str: String) = IMReads.reads(Json.parse(str))
//
//  implicit object IMReads extends Reads[InventoryMessage] {
//    def reads(json: JsValue): InventoryMessage = {
//      val description = (json \ "ArchiveDescription").as[String]
//      val Array(t, d) = description.split("-", 2)
//
//      InventoryMessage(
//        (json \ "InventoryDate").as[DateTime],
//        (json \\ "ArchiveList").map(a => new RemoteArchive(
//          null, 
//          ArchiveType.withName(t),
//          isoToDate(d),
//          (json \ "ArchiveId").as[String],
//          (json \ "Size").as[Long],
//          Hash((json \ "SHA256TreeHash").as[String]))).toList)
//    }
//  }
//}