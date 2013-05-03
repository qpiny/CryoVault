package org.rejna.cryo.models

import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

case class DateFormatter(date: DateTime) {
  def toISOString = ISODateTimeFormat.dateTimeNoMillis().print(date)
}

object DateUtil {
  implicit def toDateformatter(date: DateTime) = DateFormatter(date)
}