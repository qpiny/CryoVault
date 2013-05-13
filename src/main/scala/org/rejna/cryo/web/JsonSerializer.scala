package org.rejna.cryo.web

import net.liftweb.json._

import org.rejna.cryo.models.{ Snapshot, AttributeChange, AttributeListChange }

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
      ("fileSelection" -> s.fileFilters)
    case (k, v) =>
      (k.toString -> Extraction.decompose(v))
    case fe: FileElement =>
      ("file" -> fe.file.getName) ~
      ("isDirectory" -> fe.file.isDirectory()) ~ 
      ("count" -> fe.count) ~
      ("size" -> fe.size)  ~
      ("filter" -> fe.filter)
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
  }
}