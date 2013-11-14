package org.rejna.cryo.web
//
//import java.nio.file.Files
//
//import net.liftweb.json._
//
//import org.rejna.cryo.models._

import scala.reflect.runtime.{ universe => ru }

object CryoTypeTransformer extends Function1[ru.Type, ru.Type] {
  // => SwaggerModelProperty ?
  
  private def isEnumeration(tpe: ru.Type): Boolean = {
    tpe.declaration(ru.newTermName("scala$Enumeration$$outerEnum")) != ru.NoSymbol
  }
  
  def apply(from: ru.Type) = {
    if (isEnumeration(from))
      ru.typeOf[String]
    else from
  }
}
//
//object JsonSerializer extends Serializer[Int] {
//  import net.liftweb.json.JsonDSL._
//  val SnapshotClass = classOf[Int]
//
//  def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, JValue), Int] = {
//    case (TypeInfo(SnapshotClass, _), json) => sys.error("plop")
//  }
//
//  def serialize(implicit format: Formats): PartialFunction[Any, JValue] = {
////    case s: Snapshot =>
////      ("date" -> s.date.getMillis) ~
////        ("id" -> s.id) ~
////        ("size" -> s.size) ~
////        ("status" -> s.state.toString) ~
////        ("fileSelection" -> s.fileFilters.map { case (file, filter) => (file, filter.toString) })
//    case (k, v) =>
//      (k.toString -> Extraction.decompose(v))
////    case fe: FileElement =>
////      ("file" -> fe.file.getFileName.toString) ~
////        ("isDirectory" -> Files.isDirectory(fe.file)) ~
////        ("count" -> fe.count) ~
////        ("size" -> fe.size) ~
////        ("filter" -> fe.filter.toString)
////    case ac: AttributeChange[_] =>
////      ("type" -> "AttributeChange") ~
////        ("path" -> ac.path) ~
////        ("before" -> Extraction.decompose(ac.attribute.previous)) ~
////        ("after" -> Extraction.decompose(ac.attribute.now))
////    case alc: AttributeListChange[_] =>
////      ("type" -> "AttributeListChange") ~
////        ("path" -> alc.path) ~
////        ("addedValues" -> Extraction.decompose(alc.addedValues)) ~
////        ("removedValues" -> Extraction.decompose(alc.removedValues))
////    case l: Log =>
////      ("type" -> "Log") ~
////        ("path" -> l.path) ~
////        ("level" -> l.level.toString) ~
////        ("marker" -> l.marker) ~
////        ("message" -> l.message)
////    case ff: FileFilter =>
////      ff.toString
//  }
//}