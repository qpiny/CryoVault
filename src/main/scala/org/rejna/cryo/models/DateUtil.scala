package org.rejna.cryo.models

import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

object DateUtil {
  def toISOString(date: DateTime) = ISODateTimeFormat.dateTimeNoMillis().print(date)
  def fromISOString(date: String) = ISODateTimeFormat.dateTimeNoMillis.parseDateTime(date)
}