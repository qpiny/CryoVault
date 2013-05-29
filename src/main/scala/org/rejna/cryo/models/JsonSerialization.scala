package org.rejna.cryo.models

import net.liftweb.json._
import net.liftweb.json.JsonDSL._

object JsonSerialization {
  val format = Serialization.formats(NoTypeHints) +
    JsonJobSerialization +
    JsonNotificationSerialization +
    JsonInventoryEntrySerialization +
    JsonInventorySerialization
}

object JsonJobSerialization extends Serializer[Job] with LoggingClass {
  val JobClass = classOf[Job]

  def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, JValue), Job] = {
    case (TypeInfo(JobClass, _), json) =>
      val statusCode = (json \ "StatusCode").extract[String]
      val jobStatus = JobStatus(
        statusCode,
        (json \ "StatusMessage").extract[String])
        .getOrElse { throw MappingException(s"Invalid status: ${statusCode}", null) }
      json \ "Action" match {
        case JString("InventoryRetrieval") =>
          new InventoryJob(
            (json \ "JobId").extract[String],
            (json \ "JobDescription").extract[String],
            DateUtil.fromISOString((json \ "CreationDate").extract[String]),
            jobStatus,
            (json \ "CompletionDate").extractOpt[String].map(DateUtil.fromISOString))
        case JString("ArchiveRetrieval") =>
          val statusCode = (json \ "StatusCode").extract[String]
          new ArchiveJob(
            (json \ "JobId").extract[String],
            (json \ "JobDescription").extract[String],
            DateUtil.fromISOString((json \ "CreationDate").extract[String]),
            jobStatus,
            (json \ "CompletionDate").extractOpt[String].map(DateUtil.fromISOString),
            (json \ "ArchiveId").extract[String])
        case o: Any =>
          throw new MappingException(s"Job deserialization fails: unknown type: ${o}", null)
      }
  }

  def serialize(implicit format: Formats): PartialFunction[Any, JValue] = {
    //    case s: Snapshot =>
    //      ("date" -> s.date.getMillis) ~
    //        ("id" -> s.id) ~
    //        ("size" -> s.size) ~
    //        ("status" -> s.state.toString) ~
    //        ("fileSelection" -> s.fileFilters.map { case (file, filter) => (file, filter.toString) })
    case (k, v) =>
      (k.toString -> Extraction.decompose(v))
    //    case fe: FileElement =>
    //      ("file" -> fe.file.getFileName.toString) ~
    //        ("isDirectory" -> Files.isDirectory(fe.file)) ~
    //        ("count" -> fe.count) ~
    //        ("size" -> fe.size) ~
    //        ("filter" -> fe.filter.toString)
    //    case ac: AttributeChange[_] =>
    //      ("type" -> "AttributeChange") ~

  }
}
object JsonNotificationSerialization extends Serializer[NotificationMessage] with LoggingClass {
  val NotificationClass = classOf[NotificationMessage]

  def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, JValue), NotificationMessage] = {
    case (TypeInfo(NotificationClass, _), json) =>
      NotificationMessage(
        (json \ "Type").extract[String],
        (json \ "MessageId").extract[String],
        (json \ "TopicArn").extract[String],
        (json \ "Message").extract[String],
        DateUtil.fromISOString((json \ "Timestamp").extract[String]))
  }

  def serialize(implicit format: Formats) = new PartialFunction[Any, JValue] {
    def isDefinedAt(a: Any) = false
    def apply(a: Any) = JNull
  }
}

object JsonInventoryEntrySerialization extends Serializer[InventoryEntry] {
  val InventoryEntryClass = classOf[InventoryEntry]

  def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, JValue), InventoryEntry] = {
    case (TypeInfo(InventoryEntryClass, _), json) =>
      InventoryEntry(
        (json \ "ArchiveId").extract[String],
        (json \ "ArchiveDescription").extract[String],
        DateUtil.fromISOString((json \ "CreationDate").extract[String]),
        (json \ "Size").extract[Long],
        (json \ "SHA256TreeHash").extract[String])
  }

  def serialize(implicit format: Formats) = new PartialFunction[Any, JValue] {
    def isDefinedAt(a: Any) = false
    def apply(a: Any) = JNull
  }
}
object JsonInventorySerialization extends Serializer[InventoryMessage] {
  val InventoryClass = classOf[InventoryMessage]

  def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, JValue), InventoryMessage] = {
    case (TypeInfo(InventoryClass, _), json) =>
      InventoryMessage(
        DateUtil.fromISOString((json \ "InventoryDate").extract[String]),
        (json \ "ArchiveList").children.map(_.extract[InventoryEntry]))
  }

  def serialize(implicit format: Formats) = new PartialFunction[Any, JValue] {
    def isDefinedAt(a: Any) = false
    def apply(a: Any) = JNull
  }
}


/*

JObject(
	List(
		Type -> JString(Notification),
 		MessageId -> 7cdb74ca-f8ce-50ce-83d3-3ed45891c9a9
 		TopicArn,JString(arn:aws:sns:eu-west-1:235715319590:GlacierNotificationTopic))
		Message,JString({"Action":"InventoryRetrieval","ArchiveId":null,"ArchiveSHA256TreeHash":null,"ArchiveSizeInBytes":null,"Completed":true,"CompletionDate":"2013-05-28T12:07:59.605Z","CreationDate":"2013-05-28T08:07:56.017Z","InventorySizeInBytes":1159,"JobDescription":null,"JobId":"-rativfxujww0Jr16Fvcw5_CVOzTBYVWKYe0lEG8_iFNHBR1h_YlHxY2L9crvld6G-9Myi8SylCmBcSr6gUOzShS7T4m","RetrievalByteRange":null,"SHA256TreeHash":null,"SNSTopic":"arn:aws:sns:eu-west-1:235715319590:GlacierNotificationTopic","StatusCode":"Succeeded","StatusMessage":"Succeeded","VaultARN":"arn:aws:glacier:eu-west-1:235715319590:vaults/cryo"})), JField(Timestamp,JString(2013-05-28T12:07:59.662Z)), JField(SignatureVersion,JString(1)),
		Signature,JString(T3qjai/8h50w9xKxxO9AVMDsnKXvAz4SdJBxA2LG7/jdLPOc6qAT//EFuQsz643gslZ4Kf/chwstEi8WWC1XimKC3VprB3NVQoeYhUaaI+kBnjJpICPUV2zGDXfOaJVu8vyAnnk9Yh2cxuDeAKEQEVpiszwVG/5v5nQyTVSO7M0=)),
		JField(SigningCertURL,JString(https://sns.eu-west-1.amazonaws.com/SimpleNotificationService-f3ecfb7224c7233fe7bb5f59f96de52f.pem)), 
		JField(UnsubscribeURL,JString(https://sns.eu-west-1.amazonaws.com/?Action=Unsubscribe&SubscriptionArn=arn:aws:sns:eu-west-1:235715319590:GlacierNotificationTopic:bef0e987-6ee7-4a93-a4e5-75791eb786d5))
	)
)




 */
  /*
  		"\"Action\":\"InventoryRetrieval\",
  		"\"ArchiveId\":null" +
  		"\"ArchiveSHA256TreeHash\":null" +
  		"\"ArchiveSizeInBytes\":null" +
  		"\"Completed\":true" +
  		"\"CompletionDate\":\"2013-05-17T11:36:25.669Z\"" +
  		"\"CreationDate\":\"2013-05-17T07:36:17.140Z\"" +
  		"\"InventorySizeInBytes\":1159" +
  		"\"JobDescription\":null" +
  		"\"JobId\":\"Vg6tHfPPIo7HrsY15dMwi-E0dGmHFM8qAwvjtYP8jfbkHYuoXjB-tvH9f6oI0U8B_zwhpZ8eEFv0b3Yzp6cYX-OeMNex\"" +
  		"\"RetrievalByteRange\":null" +
  		"\"SHA256TreeHash\":null" +
  		"\"SNSTopic\":\"arn:aws:sns:eu-west-1:235715319590:GlacierNotificationTopic\"" +
  		"\"StatusCode\":\"Succeeded\"" +
  		"\"StatusMessage\":\"Succeeded\"" +
  		"\"VaultARN\":\"arn:aws:glacier:eu-west-1:235715319590:vaults/cryo\"}"
*/