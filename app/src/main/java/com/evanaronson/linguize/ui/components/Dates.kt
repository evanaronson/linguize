package com.evanaronson.linguize.ui.components

import android.content.res.Resources
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Match the current app configuration, including Android's per-app language setting. */
internal val Resources.uiLocale: Locale
    get() = configuration.locales[0] ?: Locale.getDefault()

/** A locale-appropriate short date, with the year only when it differs from this year. */
internal fun shortDate(
    at: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val moment = Instant.ofEpochMilli(at).atZone(zone)
    val thisYear = moment.year == Instant.ofEpochMilli(now).atZone(zone).year
    val pattern = DateFormat.getBestDateTimePattern(locale, if (thisYear) "MMMd" else "yMMMd")
    return DateTimeFormatter.ofPattern(pattern, locale).format(moment)
}
