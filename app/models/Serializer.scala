package models

import java.io.File
import org.apache.commons.io.filefilter._

import play.api.libs.json.{ JsString, JsObject, JsNumber, JsNull, JsValue, JsArray }

import sbinary._

import Operations._

import ArchiveType._

import org.joda.time.DateTime
//import org.joda.time.format.DateTimeFormat

object CryoBinary extends DefaultProtocol {
  import DefaultProtocol._

  implicit def IteratorFormat[A](implicit af: Format[A]) = new Format[Iterator[A]] {

    def reads(in: Input) = new Iterator[A] {
      private var _hasNext = in.readByte == 0
      def hasNext = _hasNext
      def next = {
        _hasNext = in.readByte == 0
        af.reads(in)
      }
    }

    def writes(out: Output, value: Iterator[A]) = {
      value.foreach { a =>
        out.writeByte(0)
        af.writes(out, a)
      }
      out.writeByte(1)
    }
  }
  implicit val DateTimeFormat = wrap[DateTime, Long](_.getMillis, new DateTime(_))
  implicit val ArchiveTypeFormat = wrap[ArchiveType, Int](_.id, ArchiveType.apply(_))
  implicit val HashFormat = wrap[Hash, Array[Byte]](_.value, new Hash(_))

  implicit def RemoteArchiveFormat(implicit cryo: Cryo) = asProduct5[RemoteArchive, ArchiveType, DateTime, String, Long, Hash](
    (t, d, i, s, h) => new RemoteArchive(t, d, i, s, h))(
      ra => (ra.archiveType, ra.date, ra.id, ra.size, ra.hash))
  //  
  //  implicit val ArchiveFormat = wrap[Archive, RemoteArchive](a => a match {
  //    case la: LocalArchive => la.remoteArchive.getOrElse { sys.error("Local archive is not serializable") }
  //    case ra: RemoteArchive => ra
  //    case _ =>  sys.error("Invalid archive class")
  //  }, _.asInstanceOf[Archive])

  implicit def BlockLocationFormat(implicit cryo: Cryo) = asProduct4(
    (hash: Hash, archiveId: String, offset: Long, size: Int) => BlockLocation(hash, cryo.inventory.archives(archiveId), offset, size))(
      (bl: BlockLocation) => (bl.hash, bl.archive.id, bl.offset, bl.size))

  implicit def SnapshotFormat(implicit cryo: Cryo) = wrap[Snapshot, RemoteArchive](s => s match {
    case ls: LocalSnapshot => ls.remoteSnapshot.getOrElse { sys.error("Local snapshot is not serializable") }
    case rs: RemoteSnapshot => rs
    case _ => sys.error("Invalid snapshot class")
  }, _.asInstanceOf[Snapshot])

  ////////////////////////////////////////////////////////////////////////////
  // implicit val RemoteFileFormat = asProduct3[RemoteFile, String, File, List[BlockLocation]](
  //      (i, f, bls) => new RemoteFile(Cryo.attributeBuilder.subBuilder("archive").withAlias("snapshot").subBuilder(i).subBuilder("files"), i, f, bls: _*))(
  //          rf => (rf.snapshotId, rf.file, rf.blockLocations.toList))

  //def IndexFormat(id: String) = asProduct3[(Map[Hash, BlockLocation], Map[String, List[Hash]], Map[String, String]),
  //  Map[Hash, BlockLocation], Map[String, List[Hash]], Map[String, String](_)()

  //wrap[Tuple2[File, List[BlockLocation]], RemoteFile](rf => new RemoteFile(Cryo.attributeBuilder.subBuilder("dummy"), id, rf._1, rf._2: _*), sys.error("Deserialization of Index is not permitted"))

}

object CryoJson {
  implicit def DateTimeFormat(date: DateTime) = JsString(org.joda.time.format.DateTimeFormat.shortDateTime.print(date))
  implicit def StringFormat(str: String) = JsString(str)
  implicit def StatusFormat(status: CryoStatus.CryoStatus) = JsString(status.toString)
  implicit def ArchiveTypeFormat(archiveType: ArchiveType) = JsString(archiveType.toString)
  implicit def RemoteArchiveFormat(archive: RemoteArchive) = ArchiveFormat(archive)
  implicit def SnapshotFormat(snapshot: Snapshot): JsValue = {
    JsObject(Seq(
      "status" -> JsString(snapshot.state.toString),
      "date" -> DateTimeFormat(snapshot.date),
      "size" -> JsNumber(snapshot.size),
      "id" -> JsString(snapshot.id),
      "fileSelection" -> JsObject(snapshot.fileFilters.map(kv => kv._1 -> JsString(kv._2)).toSeq)))
  }
  implicit def ArchiveFormat(archive: Archive): JsValue = {
    JsObject(Seq(
      "archiveType" -> JsString(archive.archiveType.toString),
      "status" -> JsString(archive.state.toString),
      "date" -> DateTimeFormat(archive.date),
      "size" -> JsNumber(archive.size),
      "id" -> JsString(archive.id)))
  }

  implicit def OptionFormat[A](option: Option[A])(implicit subserializer: A => JsValue) = option match {
    case Some(a) => subserializer(a)
    case None => JsNull
  }
  implicit def NumberFormat(number: Long) = JsNumber(number)
  implicit def FileFormat(file: File) = JsString(file.getAbsolutePath)
  implicit def BlockLocationFormat(bl: BlockLocation) = JsObject(Seq(
    "archive" -> ArchiveFormat(bl.archive),
    "offset" -> JsNumber(bl.offset),
    "size" -> JsNumber(bl.size)))
  implicit def RemoteFileFormat(remoteFile: RemoteFile) = JsObject(Seq(
    "snapshotId" -> JsString(remoteFile.snapshotId),
    "file" -> FileFormat(remoteFile.file),
    "blockLocation" -> JsArray(remoteFile.blockLocations.map(bl => BlockLocationFormat(bl)))))
  implicit def ListFormat[A](list: List[A])(implicit subserializer: A => JsValue) =
    JsArray(list.map(subserializer).toSeq)
  implicit def MapFormat[A](map: scala.collection.Map[String, A])(implicit subserializer: A => JsValue) =
    JsObject(map.mapValues(subserializer).toSeq)
  implicit def TupleFormat[A, B](kv: Tuple2[A, B])(implicit keySerializer: A => String, valueSerializer: B => JsValue): JsValue =
    JsObject(Seq(keySerializer(kv._1) -> valueSerializer(kv._2)))
}