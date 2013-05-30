package org.rejna.cryo.models

import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormatterBuilder

object DateUtil {
  val dateFormat = new DateTimeFormatterBuilder()
    .appendYear(4, 9)
    .appendLiteral('-')
    .appendMonthOfYear(2)
    .appendLiteral('-')
    .appendDayOfMonth(2)
    .appendLiteral('T')
    .appendHourOfDay(2)
    .appendLiteral(':')
    .appendMinuteOfHour(2)
    .appendLiteral(':')
    .appendSecondOfMinute(2)
    .appendLiteral('.')
    .appendFractionOfSecond(2, 9)
    .appendTimeZoneOffset("Z", true, 2, 4)
    .toFormatter()
  def toISOString(date: DateTime) = dateFormat.print(date)
  def fromISOString(date: String) = dateFormat.parseDateTime(date)
}