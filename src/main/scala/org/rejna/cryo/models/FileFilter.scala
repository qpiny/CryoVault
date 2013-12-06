package org.rejna.cryo.models

import scala.concurrent.duration.Duration
import scala.util.parsing.combinator.JavaTokenParsers

import java.nio.file.{ Files, Path, FileSystems }
import java.nio.file.attribute.FileTime
import java.util.Date

case class ParseError(message: String) extends Exception(message)
object FileFilterParser extends JavaTokenParsers {
  def param = "[^)]*".r
  def duration = wholeNumber ~ ident ^^ { case length ~ unit => Duration(length.toLong, unit) }
  def extension = "ext(" ~> param <~ ")" ^^ ExtensionFilter
  def older = "older(" ~> duration <~ ")" ^^ OlderFilter
  def newer = "newer(" ~> duration <~ ")" ^^ NewerFilter
  def or = "or(" ~> filter ~ "," ~ filter <~ ")" ^^ { case filter1 ~ "," ~ filter2 => Or(filter1, filter2) }
  def and = "and(" ~> filter ~ "," ~ filter <~ ")" ^^ { case filter1 ~ "," ~ filter2 => And(filter1, filter2) }
  def not = "not(" ~> filter <~ ")" ^^ Not
  def all = "all" ^^ (x => All)
  def none = "none" ^^ (x => NoOne)

  def filter: Parser[FileFilter] =
    (extension
      | older
      | newer
      | or
      | and
      | not
      | all
      | none)

  def parse(s: String): Either[String, FileFilter] = parseAll(filter, s) match {
    case Success(result, input) => Right(result)
    case NoSuccess(message, input) => Left(message)
  }
}

object FileFilter {
  def unapply(s: String): Option[FileFilter] = FileFilterParser.parse(s).right.toOption
  def apply(s: String): FileFilter = FileFilterParser.parse(s).right.get
}

sealed abstract class FileFilter {
  def accept(file: Path): Boolean
  def accept(file: String): Boolean = accept(FileSystems.getDefault.getPath(file))
}

case class ExtensionFilter(ext: String) extends FileFilter {
  def accept(file: Path) = file.getFileName.toString.endsWith(s".${ext}")
  override def toString = s"ext(${ext})"
}

case class OlderFilter(age: Duration) extends FileFilter {
  def accept(file: Path) = Files.getLastModifiedTime(file).toMillis < (System.currentTimeMillis - age.toMillis)
  override def toString = s"older(${age})"
}

case class NewerFilter(age: Duration) extends FileFilter {
  def accept(file: Path) = Files.getLastModifiedTime(file).toMillis > (System.currentTimeMillis - age.toMillis)
  override def toString = s"older(${age})"
}

case class Or(ff1: FileFilter, ff2: FileFilter) extends FileFilter {
  def accept(file: Path) = ff1.accept(file) || ff2.accept(file)
  override def toString = s"or(${ff1},${ff2})"
}

case class And(ff1: FileFilter, ff2: FileFilter) extends FileFilter {
  def accept(file: Path) = ff1.accept(file) && ff2.accept(file)
  override def toString = s"and(${ff1},${ff2})"
}

case class Not(ff: FileFilter) extends FileFilter {
  def accept(file: Path) = !ff.accept(file)
  override def toString = s"not(${ff})"
}

case object All extends FileFilter {
  def accept(file: Path) = true
  override def toString = "all"
}

case object NoOne extends FileFilter {
  def accept(file: Path) = false
  override def toString = "none"
}
