package org.rejna.cryo.models

import java.io.File

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

  implicit def RemoteArchiveFormat = asProduct5[RemoteArchive, ArchiveType, DateTime, String, Long, Hash](
    (t, d, i, s, h) => new RemoteArchive(t, d, i, s, h))(
      ra => (ra.archiveType, ra.date, ra.id, ra.size, ra.hash))
  //  
  //  implicit val ArchiveFormat = wrap[Archive, RemoteArchive](a => a match {
  //    case la: LocalArchive => la.remoteArchive.getOrElse { sys.error("Local archive is not serializable") }
  //    case ra: RemoteArchive => ra
  //    case _ =>  sys.error("Invalid archive class")
  //  }, _.asInstanceOf[Archive])

  implicit def BlockLocationFormat = asProduct4(
    (hash: Hash, archiveId: String, offset: Long, size: Int) => BlockLocation(hash, Cryo.inventory.archives(archiveId), offset, size))(
      (bl: BlockLocation) => (bl.hash, bl.archive.id, bl.offset, bl.size))

  implicit def SnapshotFormat = wrap[Snapshot, RemoteArchive](s => s match {
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