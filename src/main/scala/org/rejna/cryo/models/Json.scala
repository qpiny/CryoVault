package org.rejna.cryo.models

import akka.util.ByteString

import java.util.{ Date, UUID }
import java.nio.file.Path
import java.io.File

import org.json4s._
import org.json4s.native.Serialization
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods._
import org.json4s.ext.EnumNameSerializer

import org.joda.time.DateTime
import org.joda.time.format.{ DateTimeFormatter, DateTimeFormatterBuilder }

object Json extends Formats {
  implicit val format = this
  val fractionOfSecondFormat = new DateTimeFormatterBuilder()
    .appendLiteral('.')
    .appendFractionOfSecond(2, 9)
    .toParser()
  def jodaDateBaseFormat = new DateTimeFormatterBuilder()
    .appendYear(4, 9)
    .appendLiteral('-')
    .appendMonthOfYear(2)
    .appendLiteral('-')
    .appendDayOfMonth(2)
    .appendLiteral('T')
    .appendHourOfDay(2)
    .appendLiteral(':')
    .appendMinuteOfHour(2)
    .appendLiteral(':')
    .appendSecondOfMinute(2)
  val jodaDateFormat = new DateTimeFormatter(
    jodaDateBaseFormat
      .appendTimeZoneOffset("Z", true, 2, 4)
      .toPrinter(),
    jodaDateBaseFormat
      .appendOptional(fractionOfSecondFormat)
      .appendTimeZoneOffset("Z", true, 2, 4)
      .toParser())

  val dateFormat = new DateFormat {
    def parse(s: String) = try {
      Some(jodaDateFormat.parseDateTime(s).toDate)
    } catch {
      case t: Throwable => None
    }
    def format(d: Date): String = jodaDateFormat.print(new DateTime(d))
  }
  override val typeHints = NoTypeHints
  override val customSerializers =
    JsonDataEntry ::
    JsonFileElement ::
    JsonFilefilter ::
    JsonPathFileFilter ::
    JsonDataStatus :: Nil

  def readDate(s: String): Option[Date] = dateFormat.parse(s)
  def writeDate(date: Date): String = dateFormat.format(date)

  def read[T](json: String)(implicit mf: Manifest[T]): T = {
    Serialization.read[T](json)
  }

  def read[T](buffer: ByteString)(implicit mf: Manifest[T]): T =
    read[T](buffer.decodeString("UTF-8"))

  def write[T <: AnyRef](t: T): String = {
    Serialization.write(t)
  }
}

case object JsonDataEntry extends CustomSerializer[DataEntry](format => (
  {
    case JNull => null
  },
  {
    case de: DataEntry => Json.write(de.state)
  }))

case object JsonFileElement extends CustomSerializer[FileElement](format => (
  {
    case JNull => null
  },
  {
    case fe: FileElement =>
      ("name" -> fe.path.getFileName.toString) ~
        ("path" -> fe.path.toString.replace(File.separatorChar, '!')) ~
        ("isFolder" -> fe.isFolder) ~
        ("filter" -> fe.filter.map(_.toString)) ~
        ("count" -> fe.count) ~
        ("size" -> fe.size)
  }))

private object JsonDataStatus extends CustomSerializer[DataStatus](format => (
  {
    case o @ JObject(
      JField("id", JString(id)) ::
        JField("dataType", JString(dataType)) ::
        JField("creationDate", JString(creationDate)) ::
        JField("size", JInt(size)) ::
        JField("checksum", JString(checksum)) ::
        Nil) =>
      val status = (o \ "status") match {
        case JString(s) =>
          ObjectStatus(s)
        case _ =>
          ObjectStatus.Unknown()
      }
      DataStatus(UUID.fromString(id),
          DataType.withName(dataType),
          Json.readDate(creationDate).getOrElse(new Date),
          status, size.toLong, checksum)
  },
  {
    case ds: DataStatus =>
      ("id" -> JString(ds.id.toString)) ~
        ("dataType" -> JString(ds.dataType.toString)) ~
        ("creationDate" -> Json.writeDate(ds.creationDate)) ~
        ("status" -> ds.status.toString) ~
        ("size" -> ds.size) ~
        ("checksum" -> ds.checksum)
  }))

private object JsonPathFileFilter extends CustomSerializer[(Path, FileFilter)](format => (
  {
    case JNull => null
  },
  {
    case (path, fileFilter) =>
      (path.toString.replace(File.separatorChar, '!') -> fileFilter.toString)
  }))
  
private object JsonFilefilter extends CustomSerializer[FileFilter](format => (
  {
    case JObject(JField("filter", JString(ff)) :: Nil) => FileFilter(ff)
  },
  {
    case ff: FileFilter => JObject(JField("filter", ff.toString))
  }))
//
//  def serialize(implicit format: Formats): PartialFunction[Any, JValue] = {
//    case j: Job =>
//      ("id" -> j.id) ~
//        ("jobType" -> "InventoryJob") ~
//        ("description" -> j.description) ~
//        ("creationDate" -> Extraction.decompose(j.creationDate)) ~
//        ("status" -> j.status.toString) ~
//        ("completedDate" -> Extraction.decompose(j.completedDate)) ~
//        ("objectId" -> j.objectId)
//  }
//}
// DataStatus serializer : OK
// DataStatus deserializer : Optional status


/****************WITH TYPEHINT***********************/
//object EventTypeHints extends TypeHints {
//  val hints =
//    //    classOf[UploadSnapshot] ::
//    //      classOf[UpdateSnapshotFileFilter] ::
//    classOf[Unsubscribe] ::
//      classOf[Subscribe] ::
//      classOf[RemoveIgnoreSubscription] ::
//      classOf[RefreshInventory] ::
//      classOf[GetSnapshotList] ::
//      classOf[SnapshotGetFiles] ::
//      //      classOf[GetArchiveList] ::
//      classOf[CreateSnapshot] ::
//      classOf[SnapshotCreated] ::
//      classOf[SnapshotCreated] ::
//      classOf[AddIgnoreSubscription] ::
//      //      classOf[ArchiveList] ::
//      classOf[SnapshotList] ::
//      //      classOf[AddFile] ::
//      //      //classOf[ArchiveCreation] ::
//      //      classOf[SnapshotFiles] ::
//      classOf[AttributeChange[_]] ::
//      classOf[AttributeListChange[_]] ::
//      classOf[CryoError] ::
//      classOf[Log] ::
//      classOf[GetJobList] ::
//      classOf[JobList] ::
//      classOf[Exit] ::
//      Nil
//
//  def hintFor(clazz: Class[_]) = clazz.getSimpleName
//  def classFor(hint: String) = hints find (hintFor(_) == hint)
//}
//object EventSerialization {
//  implicit object CryoFormats extends Formats {
//    override val typeHintFieldName = "type"
//    override val typeHints = EventTypeHints
//    override val customSerializers = Json.customSerializers
//    val dateFormat = Json.dateFormat
//  }
//}
/****************WITH TYPEHINT***********************/

//extends Formats {
//  override val customSerializers =
//    JsonJobSerialization ::
//      JsonNotificationSerialization ::
//      JsonInventoryEntrySerialization ::
//      JsonInventorySerialization ::
//      new EnumNameSerializer(EntryStatus) ::
//      JsonLogSerialization ::
//      JsonTupleSerialization ::
//      Nil
//
//  val fractionOfSecondFormat = new DateTimeFormatterBuilder()
//    .appendLiteral('.')
//    .appendFractionOfSecond(2, 9)
//    .toParser()
//  val jodaDateFormat = new DateTimeFormatterBuilder()
//    .appendYear(4, 9)
//    .appendLiteral('-')
//    .appendMonthOfYear(2)
//    .appendLiteral('-')
//    .appendDayOfMonth(2)
//    .appendLiteral('T')
//    .appendHourOfDay(2)
//    .appendLiteral(':')
//    .appendMinuteOfHour(2)
//    .appendLiteral(':')
//    .appendSecondOfMinute(2)
//    .appendOptional(fractionOfSecondFormat)
//    .appendTimeZoneOffset("Z", true, 2, 4)
//    .toFormatter()
//
//  val dateFormat = new DateFormat {
//    def parse(s: String) = try {
//      Some(jodaDateFormat.parseDateTime(s).toDate)
//    } catch {
//      case t: Throwable => None
//    }
//    def format(d: Date): String = jodaDateFormat.print(new DateTime(d))
//  }
//}
//
//object JsonTupleSerialization extends Serializer[Null] {
//  
//  def deserialize(implicit format: Formats) = new PartialFunction[(TypeInfo, JValue), Null] {
//    def isDefinedAt(a: (TypeInfo, JValue)) = false
//    def apply(a: (TypeInfo, JValue)) = null
//  }
//  
//  def serialize(implicit format: Formats): PartialFunction[Any, JValue] = {
//    case (k, v)  => (k.toString -> Extraction.decompose(v))
//  }
//}
//  
//object JsonJobSerialization extends Serializer[Job] {
//  val JobClass = classOf[Job]
//
//  def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, JValue), Job] = {
//    case (TypeInfo(JobClass, _), json) =>
//      val statusCode = (json \ "StatusCode").extract[String]
//      val jobStatus = JobStatus(
//        statusCode,
//        (json \ "StatusMessage").extract[String])
//        .getOrElse { throw MappingException(s"Invalid status: ${statusCode}", null) }
//      json \ "Action" match {
//        case JString("InventoryRetrieval") =>
//          new Job(
//            (json \ "JobId").extract[String],
//            (json \ "JobDescription").extractOpt[String].getOrElse(""),
//            (json \ "CreationDate").extract[Date],
//            jobStatus,
//            (json \ "CompletionDate").extractOpt[Date],
//            "inventory")
//        case JString("ArchiveRetrieval") =>
//          val statusCode = (json \ "StatusCode").extract[String]
//          new Job(
//            (json \ "JobId").extract[String],
//            (json \ "JobDescription").extractOpt[String].getOrElse(""),
//            (json \ "CreationDate").extract[Date],
//            jobStatus,
//            (json \ "CompletionDate").extractOpt[Date],
//            (json \ "ArchiveId").extract[String])
//        case o: Any =>
//          throw new MappingException(s"Job deserialization fails: unknown type: ${o}", null)
//      }
//  }
//
//  def serialize(implicit format: Formats): PartialFunction[Any, JValue] = {
//    case j: Job =>
//      ("id" -> j.id) ~
//        ("jobType" -> "InventoryJob") ~
//        ("description" -> j.description) ~
//        ("creationDate" -> Extraction.decompose(j.creationDate)) ~
//        ("status" -> j.status.toString) ~
//        ("completedDate" -> Extraction.decompose(j.completedDate)) ~
//        ("objectId" -> j.objectId)
//  }
//}
//object JsonNotificationSerialization extends Serializer[NotificationMessage] {
//  val NotificationClass = classOf[NotificationMessage]
//
//  def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, JValue), NotificationMessage] = {
//    case (TypeInfo(NotificationClass, _), json) =>
//      NotificationMessage(
//        (json \ "Type").extract[String],
//        (json \ "MessageId").extract[String],
//        (json \ "TopicArn").extract[String],
//        (json \ "Message").extract[String],
//        (json \ "Timestamp").extract[Date])
//  }
//
//  def serialize(implicit format: Formats) = new PartialFunction[Any, JValue] {
//    def isDefinedAt(a: Any) = false
//    def apply(a: Any) = JNull
//  }
//}
//
//object JsonInventoryEntrySerialization extends Serializer[InventoryEntry] {
//  val InventoryEntryClass = classOf[InventoryEntry]
//
//  def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, JValue), InventoryEntry] = {
//    case (TypeInfo(InventoryEntryClass, _), json) =>
//      InventoryEntry(
//        (json \ "ArchiveId").extract[String],
//        (json \ "ArchiveDescription").extract[String],
//        (json \ "CreationDate").extract[Date],
//        (json \ "Size").extract[Long],
//        (json \ "SHA256TreeHash").extract[String])
//  }
//
//  def serialize(implicit format: Formats) = new PartialFunction[Any, JValue] {
//    def isDefinedAt(a: Any) = false
//    def apply(a: Any) = JNull
//  }
//}
//
//object JsonInventorySerialization extends Serializer[InventoryMessage] {
//  val InventoryClass = classOf[InventoryMessage]
//
//  def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, JValue), InventoryMessage] = {
//    case (TypeInfo(InventoryClass, _), json) =>
//      InventoryMessage(
//        (json \ "InventoryDate").extract[Date],
//        (json \ "ArchiveList").children.map(_.extract[InventoryEntry]))
//  }
//
//  def serialize(implicit format: Formats) = new PartialFunction[Any, JValue] {
//    def isDefinedAt(a: Any) = false
//    def apply(a: Any) = JNull
//  }
//}
//
////object JsonDateTimeSerialization extends Serializer[DateTime] with LoggingClass {
////  val DateTimeClass = classOf[DateTime]
////
////  def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, JValue), DateTime] = {
////    case (TypeInfo(DateTimeClass, _), json) =>
////      json match {
////        case s: JString => Json.jodaDateFormat.parseDateTime(s.values)
////        case _: Any => throw new MappingException(s"Can't convert DataTime from ${json}")
////      }
////  }
////
////  def serialize(implicit format: Formats) = { //new PartialFunction[Any, JValue] {
//////    def isDefinedAt(a: Any) = a.isInstanceOf[DateTime]
//////    def apply(a: Any) = a match {
////      case dt: DateTime => JString(Json.jodaDateFormat.print(dt))
//////    }
////  }
////}
//
//object JsonDataStatusSerialization extends Serializer[DataStatus] {
//  val DataStatusClass = classOf[DataStatus]
//
//  def deserialize(implicit format: Formats) = new PartialFunction[(TypeInfo, JValue), DataStatus] {
//    def isDefinedAt(a: (TypeInfo, JValue)) = false
//    def apply(a: (TypeInfo, JValue)) = null
//  }
//
//  def serialize(implicit format: Formats) = {
//    case ds: DataStatus =>
//      ("id" -> ds.id) ~
//        ("description" -> ds.description) ~
//        ("creationDate" -> Extraction.decompose(ds.creationDate)) ~
//        ("checksum" -> ds.checksum) ~
//        ("size" -> ds.size) ~
//        ("status" -> Extraction.decompose(ds.status))
//  }
//}
//
//object JsonLogSerialization extends Serializer[Log] {
//  val LogClass = classOf[Log]
//
//  def deserialize(implicit format: Formats) = new PartialFunction[(TypeInfo, JValue), Log] {
//    def isDefinedAt(a: (TypeInfo, JValue)) = false
//    def apply(a: (TypeInfo, JValue)) = null
//  }
//
//  def serialize(implicit format: Formats) = {
//    case log: Log =>
//      ("type" -> "Log") ~
//        ("level" -> log.level.levelStr) ~
//        ("message" -> log.message)
//  }
//}
//
////=>"{\"type\":\"SnapshotList\",\"snapshots\":[" +
////		"{\"id\":\"CP6-biSqTKya2U7tMOgBL-wO_AunwkURiqMcdXtYg1szqtxL8QHtlDVQlL-cX1Co-5YOEhUO8pfTAx9lVZiz-swZ-pGstAgFAm6Ag2Dmci93f1YHKmwaQWrxTuHV9wqoczKc_lNlAg\"," +
////		"\"description\":\"Index-2013-05-15T22:03:04+02:00\"," +
////		"\"creationDate\":{},\"status\":{\"name\":null},\"size\":1040648,\"checksum\":\"1839a063dbe16b913c21e2568315bd324541a93b419552c3ad9b2c11ec1aef68\"},{\"id\":\"NTGTdpaFplQJXa6JZhNh-sm35_ADbnZdf9maGwwmHbokjFB0CO-3DLsynfVjyrmuqHw0takd-DPD-uKn8Z6FRFXnji7o70FgqveDl_O1r4aO42hLLwZkZhhxNtfTbygjgJZ_G97LFg\",\"description\":\"Index-2013-05-15T22:15:43+02:00\",\"creationDate\":{},\"status\":{\"name\":null},\"size\":821552,\"checksum\":\"e25217d32ffbef95eb66cf8adbdc5bda1648424cba295e8d090a021acdbf6fff\"}]}"
//
///*
//
//JObject(
//	List(
//		Type -> JString(Notification),
// 		MessageId -> 7cdb74ca-f8ce-50ce-83d3-3ed45891c9a9
// 		TopicArn,JString(arn:aws:sns:eu-west-1:235715319590:GlacierNotificationTopic))
//		Message,JString({"Action":"InventoryRetrieval","ArchiveId":null,"ArchiveSHA256TreeHash":null,"ArchiveSizeInBytes":null,"Completed":true,"CompletionDate":"2013-05-28T12:07:59.605Z","CreationDate":"2013-05-28T08:07:56.017Z","InventorySizeInBytes":1159,"JobDescription":null,"JobId":"-rativfxujww0Jr16Fvcw5_CVOzTBYVWKYe0lEG8_iFNHBR1h_YlHxY2L9crvld6G-9Myi8SylCmBcSr6gUOzShS7T4m","RetrievalByteRange":null,"SHA256TreeHash":null,"SNSTopic":"arn:aws:sns:eu-west-1:235715319590:GlacierNotificationTopic","StatusCode":"Succeeded","StatusMessage":"Succeeded","VaultARN":"arn:aws:glacier:eu-west-1:235715319590:vaults/cryo"})), JField(Timestamp,JString(2013-05-28T12:07:59.662Z)), JField(SignatureVersion,JString(1)),
//		Signature,JString(T3qjai/8h50w9xKxxO9AVMDsnKXvAz4SdJBxA2LG7/jdLPOc6qAT//EFuQsz643gslZ4Kf/chwstEi8WWC1XimKC3VprB3NVQoeYhUaaI+kBnjJpICPUV2zGDXfOaJVu8vyAnnk9Yh2cxuDeAKEQEVpiszwVG/5v5nQyTVSO7M0=)),
//		JField(SigningCertURL,JString(https://sns.eu-west-1.amazonaws.com/SimpleNotificationService-f3ecfb7224c7233fe7bb5f59f96de52f.pem)), 
//		JField(UnsubscribeURL,JString(https://sns.eu-west-1.amazonaws.com/?Action=Unsubscribe&SubscriptionArn=arn:aws:sns:eu-west-1:235715319590:GlacierNotificationTopic:bef0e987-6ee7-4a93-a4e5-75791eb786d5))
//	)
//)
//
//
//
//
// */
//  /*
//  		"\"Action\":\"InventoryRetrieval\",
//  		"\"ArchiveId\":null" +
//  		"\"ArchiveSHA256TreeHash\":null" +
//  		"\"ArchiveSizeInBytes\":null" +
//  		"\"Completed\":true" +
//  		"\"CompletionDate\":\"2013-05-17T11:36:25.669Z\"" +
//  		"\"CreationDate\":\"2013-05-17T07:36:17.140Z\"" +
//  		"\"InventorySizeInBytes\":1159" +
//  		"\"JobDescription\":null" +
//  		"\"JobId\":\"Vg6tHfPPIo7HrsY15dMwi-E0dGmHFM8qAwvjtYP8jfbkHYuoXjB-tvH9f6oI0U8B_zwhpZ8eEFv0b3Yzp6cYX-OeMNex\"" +
//  		"\"RetrievalByteRange\":null" +
//  		"\"SHA256TreeHash\":null" +
//  		"\"SNSTopic\":\"arn:aws:sns:eu-west-1:235715319590:GlacierNotificationTopic\"" +
//  		"\"StatusCode\":\"Succeeded\"" +
//  		"\"StatusMessage\":\"Succeeded\"" +
//  		"\"VaultARN\":\"arn:aws:glacier:eu-west-1:235715319590:vaults/cryo\"}"
//*/