package org.rejna.cryo.models

import org.joda.time.DateTime

import org.rejna.util.IsoUnit

trait ProgressStatus {
  def title: String
  def progress: Int
  def label: String
}

trait Transfer extends ProgressStatus {
  val attributeBuilder: AttributeBuilder

  val transferredBytesAttribute = attributeBuilder("transferredBytes", 0L)
  def transferredBytes = transferredBytesAttribute()
  def transferredBytes_= = transferredBytesAttribute() = _

  val totalBytesAttribute = attributeBuilder("totalBytes", 0L)
  def totalBytes = totalBytesAttribute()
  def totalBytes_= = totalBytesAttribute() = _

  val progressAttribute = attributeBuilder("progress", () => {
    if (totalBytes > 0)
      (100 * transferredBytes / totalBytes).toLong
    else
      0L
  })
  progressAttribute <* totalBytesAttribute <* transferredBytesAttribute
  def progress = progressAttribute().toInt

  def labelAttribute = attributeBuilder("label", () => IsoUnit(transferredBytes) + "B/" + IsoUnit(totalBytes) + "B")
  labelAttribute <* totalBytesAttribute <* transferredBytesAttribute
  def label = labelAttribute()
}