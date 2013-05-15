package org.rejna.cryo.models

import sbinary._
import sbinary.Operations._

import org.joda.time.DateTime

import ArchiveType._

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
  implicit val FileFilterFormat = wrap[FileFilter, String](_.toString, FileFilterParser.parse(_) match {
    case Right(ff) => ff
    case Left(message) => throw ParseError(message)
  })

  implicit def RemoteArchiveFormat = asProduct5[RemoteArchive, ArchiveType, DateTime, String, Long, Hash](
    (t, d, i, s, h) => new RemoteArchive(t, d, i, s, h))(
      ra => (ra.archiveType, ra.date, ra.id, ra.size, ra.hash))

  implicit def BlockLocationFormat = asProduct4(
    (hash: Hash, archiveId: String, offset: Long, size: Int) => BlockLocation(hash, Cryo.inventory.archives(archiveId), offset, size))(
      (bl: BlockLocation) => (bl.hash, bl.archive.id, bl.offset, bl.size))

  implicit def SnapshotFormat = wrap[Snapshot, RemoteArchive](s => s match {
    case ls: LocalSnapshot => ls.remoteSnapshot.getOrElse { sys.error("Local snapshot is not serializable") }
    case rs: RemoteSnapshot => rs
    case _ => sys.error("Invalid snapshot class")
  }, _.asInstanceOf[Snapshot])
}