package org.rejna.cryo.web

import net.liftweb.json._

import org.rejna.cryo.models.Snapshot

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
  }
}