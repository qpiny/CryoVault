//package org.rejna.cryo.models
//
//import com.amazonaws.services.glacier.model.DescribeJobResult
//import com.amazonaws.services.glacier.model.GlacierJobDescription
//
//import akka.actor.ActorRef
//import java.util.Date
//import org.joda.time.DateTime
//import org.joda.time.format.ISODateTimeFormat
//
//import net.liftweb.json._
//
///* Communication between Glacier and Cryo */
//
//
////JField("ArchiveDescription", JString(description)) ::
//
////  implicit def inventoryRead(json: JsValue) = {
////    val description = (json \ "ArchiveDescription").as[String]
////    val Array(t, d) = description.split("-", 2)
////    var archiveType = ArchiveType.withName(t)
////
////    new InventoryMessage(
////      (json \ "InventoryDate").as[DateTime],
////      (json \\ "ArchiveList").map(a => new RemoteArchive(
////        archiveType,
////        isoToDate(d),
////        (json \ "ArchiveId").as[String],
////        (json \ "Size").as[Long],
////        Hash((json \ "SHA256TreeHash").as[String]))).toList)
////  }
////}
///*
//case class JobStatusMessage(
//  action: String,
//  archiveId: Option[String],
//  archiveSize: Option[Long],
//  completed: Boolean,
//  completionDate: Option[DateTime],
//  creationDate: DateTime,
//  inventorySize: Option[Long],
//  jobDescription: String,
//  jobId: String,
//  hash: Option[Hash],
//  snsTopic: String,
//  statusCode: String,
//  statusMessage: String,
//  vaultArn: String)
//
//object JobStatusMessage extends Message {
//  def apply(str: String) = AJSReads.reads(Json.parse(str))
//  def apply(djr: DescribeJobResult): JobStatusMessage = {
//    JobStatusMessage(
//      djr.getAction,
//      Option(djr.getArchiveId),
//      Option(djr.getArchiveSizeInBytes),
//      djr.getCompleted,
//      Option(djr.getCompletionDate).map(d => isoToDate(d)),
//      isoToDate(djr.getCreationDate),
//      Option(djr.getInventorySizeInBytes),
//      djr.getJobDescription,
//      djr.getJobId,
//      Option(djr.getSHA256TreeHash).map(Hash(_)),
//      djr.getSNSTopic,
//      djr.getStatusCode,
//      djr.getStatusMessage,
//      djr.getVaultARN)
//  }
//
//  implicit object AJSReads extends Reads[JobStatusMessage] {
//    def reads(json: JsValue): JobStatusMessage = JobStatusMessage(
//      (json \ "Action").as[String],
//      (json \ "ArchiveId").asOpt[String],
//      (json \ "ArchiveSizeInBytes").asOpt[Long],
//      (json \ "Completed").as[Boolean],
//      (json \ "CompletionDate").asOpt[DateTime], //2012-05-15T17:21:39.33,
//      (json \ "CreationDate").as[DateTime],
//      (json \ "InventorySizeInBytes").asOpt[Long],
//      (json \ "JobDescription").as[String],
//      (json \ "JobId").as[String],
//      (json \ "SHA256TreeHash").asOpt[String].map(Hash(_)),
//      (json \ "SNSTopic").as[String],
//      (json \ "StatusCode").as[String],
//      (json \ "StatusMessage").as[String],
//      (json \ "VaultARN").as[String])
//  }
//}
//*/
//case class InventoryMessage(val date: DateTime, val archives: List[RemoteArchive])
//object InventoryMessage {
//  implicit val format = new DefaultFormats {
//    override val dateFormat = new DateFormat {
//      def parse(s: String): Option[Date] =
//        try {
//          Some(ISODateTimeFormat.dateTimeNoMillis.parseDateTime(s).toDate)
//        } catch {
//          case _: IllegalArgumentException => None
//        }
//      def format(d: Date): String = ISODateTimeFormat.dateTimeNoMillis().print(new DateTime(d))
//    }
//  }
//  def apply(msg: String): InventoryMessage = apply(parse(msg))
//  def apply(json: JValue) = json.extract[InventoryMessage]
//}
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





/*

{
  "Type" : "Notification",
  "MessageId" : "ac1ae29d-d193-553b-9a97-a88dc9ea701e",
  "TopicArn" : "arn:aws:sns:eu-west-1:235715319590:GlacierNotificationTopic",
  "Message" : "{\"Action\":\"InventoryRetrieval\",\"ArchiveId\":null,\"ArchiveSHA256TreeHash\":null,\"ArchiveSizeInBytes\":null,\"Completed\":true,\"CompletionDate\":\"2013-05-17T11:36:25.669Z\",\"CreationDate\":\"2013-05-17T07:36:17.140Z\",\"InventorySizeInBytes\":1159,\"JobDescription\":null,\"JobId\":\"Vg6tHfPPIo7HrsY15dMwi-E0dGmHFM8qAwvjtYP8jfbkHYuoXjB-tvH9f6oI0U8B_zwhpZ8eEFv0b3Yzp6cYX-OeMNex\",\"RetrievalByteRange\":null,\"SHA256TreeHash\":null,\"SNSTopic\":\"arn:aws:sns:eu-west-1:235715319590:GlacierNotificationTopic\",\"StatusCode\":\"Succeeded\",\"StatusMessage\":\"Succeeded\",\"VaultARN\":\"arn:aws:glacier:eu-west-1:235715319590:vaults/cryo\"}",
  "Timestamp" : "2013-05-17T11:36:25.752Z",
  "SignatureVersion" : "1",
  "Signature" : "Kss0qWBDaYq7ANfFmweyWnzqo/dTaq4Ql3jKK7Lm/4fsfbv0OaFFnG5yNGaswxBVK90vDxiwLc9w2TF7OaEgqRHcjD2GCx4YFqdatY2Px8WziBm5dpLhWcuvqJzxxbpV5SH7qhqaZUrESi5IJxnNfLLWUYtlVhzlCdpQr8Kf3Ds=",
  "SigningCertURL" : "https://sns.eu-west-1.amazonaws.com/SimpleNotificationService-f3ecfb7224c7233fe7bb5f59f96de52f.pem",
  "UnsubscribeURL" : "https://sns.eu-west-1.amazonaws.com/?Action=Unsubscribe&SubscriptionArn=arn:aws:sns:eu-west-1:235715319590:GlacierNotificationTopic:65e5dbb6-ee7c-484c-8a40-d0641c4ace49"
}

*/