package org.rejna.cryo.models

import org.scalatest.FlatSpec

class FileFilterTest extends FlatSpec {
  "File filter" must "parse correctly filters" in {
    val got = FileFilter("ext(txt)").toString
    val expected = "ext(txt)"
    got === expected 
    //FileFilter("ext(txt)").toString === "ext(txt)"
  }
}