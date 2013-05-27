package org.rejna.cryo.models

import net.liftweb.json._

object JsonSerialization extends Serializer[Job] {
  import net.liftweb.json.JsonDSL._
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