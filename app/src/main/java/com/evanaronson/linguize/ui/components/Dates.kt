package com.evanaronson.linguize.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The one locale for dates and times in the app: the UI is English, so "Oct" never turns into "oct.". */
internal val DateLocale: Locale = Locale.ENGLISH

/** "9 Oct", with the year when it isn't this year. */
internal fun shortDate(at: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val moment = Instant.ofEpochMilli(at).atZone(zone)
    val thisYear = moment.year == Instant.ofEpochMilli(now).atZone(zone).year
    return DateTimeFormatter.ofPattern(if (thisYear) "d MMM" else "d MMM yyyy", DateLocale).format(moment)
}
