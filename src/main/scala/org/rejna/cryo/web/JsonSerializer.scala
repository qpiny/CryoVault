package org.rejna.cryo.web

import java.nio.file.Files

import net.liftweb.json._

import org.rejna.cryo.models._

object JsonSerializer extends Serializer[Snapshot] {
  import net.liftweb.json.JsonDSL._
  val SnapshotClass = classOf[Snapshot]

  def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, JValue), Snapshot] = {
    case (TypeInfo(SnapshotClass, _), json) => sys.error("plop")
  }

  def serialize(implicit format: Formats): PartialFunction[Any, JValue] = {
    case s: Snapshot =>
      ("date" -> s.date.getMillis) ~
        ("id" -> s.id) ~
        ("size" -> s.size) ~
        ("status" -> s.state.toString) ~
        ("fileSelection" -> s.fileFilters.map { case (file, filter) => (file, filter.toString) })
    case (k, v) =>
      (k.toString -> Extraction.decompose(v))
    case fe: FileElement =>
      ("file" -> fe.file.getFileName.toString) ~
        ("isDirectory" -> Files.isDirectory(fe.file)) ~
        ("count" -> fe.count) ~
        ("size" -> fe.size) ~
        ("filter" -> fe.filter.toString)
    case ac: AttributeChange[_] =>
      ("type" -> "AttributeChange") ~
        ("path" -> ac.path) ~
        ("before" -> Extraction.decompose(ac.attribute.previous)) ~
        ("after" -> Extraction.decompose(ac.attribute.now))
    case alc: AttributeListChange[_] =>
      ("type" -> "AttributeListChange") ~
        ("path" -> alc.path) ~
        ("addedValues" -> Extraction.decompose(alc.addedValues)) ~
        ("removedValues" -> Extraction.decompose(alc.removedValues))
    case l: Log =>
      ("type" -> "Log") ~
        ("path" -> l.path) ~
        ("level" -> l.level.toString) ~
        ("marker" -> l.marker) ~
        ("message" -> l.message)
    case ff: FileFilter =>
      ff.toString
  }
}