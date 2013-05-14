package org.rejna.cryo.models

import java.nio.file.Path

sealed abstract class FileFilter {
  def accept(file: Path): Boolean
}

case class ExtensionFilter(ext: String) extends FileFilter {
  def accept(file: Path) = file.getFileName.toString.endsWith(s".${ext}")
}

case class Or(ff1: FileFilter, ff2: FileFilter) extends FileFilter {
  def accept(file: Path) = ff1.accept(file) || ff2.accept(file)
}

case class And(ff1: FileFilter, ff2: FileFilter) extends FileFilter {
  def accept(file: Path) = ff1.accept(file) && ff2.accept(file)
}
