package org.rejna.cryo.models

import java.nio.file.Path

trait FileFilter {
  def accept(file: Path): Boolean
}