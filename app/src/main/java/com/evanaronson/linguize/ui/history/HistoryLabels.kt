package com.evanaronson.linguize.ui.history

import android.content.res.Resources
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.SessionSummary
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The same localized date label is used in Recent and on the check's detail page. */
internal fun relativeDate(
    resources: Resources,
    at: Long,
    now: Long,
    time: DateTimeFormatter,
    zone: ZoneId = ZoneId.systemDefault(),
    alwaysTime: Boolean = false,
): String {
    val moment = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (moment.toLocalDate()) {
        today -> resources.getString(R.string.date_today, time.format(moment))
        today.minusDays(1) -> resources.getString(R.string.date_yesterday, time.format(moment))
        else -> shortDate(at, now, zone, resources.uiLocale).let {
            if (alwaysTime) resources.getString(R.string.date_and_time, it, time.format(moment)) else it
        }
    }
}

/** The first line with something on it, for a one-glance preview. */
internal fun firstLine(text: String): String = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

/** What came of a check, formatted at display time so saved history follows the UI language. */
internal fun SessionSummary.statusLine(resources: Resources): String = when {
    outcome == Outcome.Failed -> resources.getString(R.string.check_failed)
    outcome == Outcome.Abandoned -> resources.getString(R.string.end_not_saved)
    status == Verdict.Status.Unclear -> resources.getString(R.string.meaning_unclear)
    status == Verdict.Status.WrongLanguage -> resources.getString(R.string.wrong_language_status)
    // Summary rows have counts, not the reasons proposed changes were withheld.
    fixes + rewordings == 0 -> resources.getString(if (status == Verdict.Status.Ok) R.string.no_changes else R.string.no_result)
    else -> listOfNotNull(
        fixes.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.fix_count, it, it) },
        rewordings.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.rewording_count, it, it) },
        when {
            taken > 0 -> resources.getQuantityString(R.plurals.accepted_count, taken, taken)
            outcome == Outcome.Copied -> resources.getString(R.string.copied_status)
            else -> null
        },
    ).joinToString(resources.getString(R.string.status_separator))
}

/** Stored codes are localized only at display time; unknown stays explicit. */
internal fun targetLanguageLabel(resources: Resources, target: String?, requested: String?): String {
    val name = target?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(resources.uiLocale) }
        ?.takeIf { it.isNotBlank() } ?: return resources.getString(R.string.language_unknown)
    return if (requested == null) resources.getString(R.string.language_auto_detected, name) else name
}

internal fun detectedLanguageLabel(resources: Resources, code: String): String =
    java.util.Locale.forLanguageTag(code).getDisplayLanguage(resources.uiLocale)
