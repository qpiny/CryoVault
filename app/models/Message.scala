package models

import play.api.libs.json._

import com.amazonaws.services.glacier.model.DescribeJobResult

import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

/* Communication between Glacier and Cryo */

object Message {
  def isoToDate(date: String): DateTime = ISODateTimeFormat.dateTimeNoMillis.parseDateTime(date)
  def dateToIso(date: DateTime): String = ISODateTimeFormat.dateTimeNoMillis().print(date)

  implicit object DateReads extends Reads[DateTime] {
    def reads(json: JsValue) = json match {
      case JsString(s) => isoToDate(s)
      case _ => sys.error("")
    }
  }

  implicit def inventoryRead(json: JsValue)(implicit cryo: Cryo) = {
      val description = (json \ "ArchiveDescription").as[String]
      val Array(t, d) = description.split("-", 2)
      var archiveType = ArchiveType.withName(t)

      new InventoryMessage(
        (json \ "InventoryDate").as[DateTime],
        (json \\ "ArchiveList").map(a => new RemoteArchive(
          archiveType,
          isoToDate(d),
          (json \ "ArchiveId").as[String],
          (json \ "Size").as[Long],
          Hash((json \ "SHA256TreeHash").as[String]))).toList)
  }
}
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
class InventoryMessage(val date: DateTime, val archives: List[RemoteArchive])
object InventoryMessage {
  def apply(json: JsValue)(implicit cryo: Cryo) = {
    val description = (json \ "ArchiveDescription").as[String]
    val Array(t, d) = description.split("-", 2)

      new InventoryMessage(
        (json \ "InventoryDate").as[DateTime](Message.DateReads),
        (json \\ "ArchiveList").map(a => new RemoteArchive(
          ArchiveType.withName(t),
          Message.isoToDate(d),
          (json \ "ArchiveId").as[String],
          (json \ "Size").as[Long],
          Hash((json \ "SHA256TreeHash").as[String]))).toList)
  }
  def apply(msg: String)(implicit cryo: Cryo): InventoryMessage = apply(Json.parse(msg))
}

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
//          null, // FIXME Cryo.attributeBuilder.subBuilder("archive"),
//          ArchiveType.withName(t),
//          isoToDate(d),
//          (json \ "ArchiveId").as[String],
//          (json \ "Size").as[Long],
//          Hash((json \ "SHA256TreeHash").as[String]))).toList)
//    }
//  }
//}