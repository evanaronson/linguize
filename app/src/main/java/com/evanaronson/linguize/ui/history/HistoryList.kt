package com.evanaronson.linguize.ui.history

import android.content.res.Resources
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.chat.ChatThreadInfo
import com.evanaronson.linguize.core.language.LanguageScope
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * History is one list of messages: every recorded message is one row of the same shape,
 * whether it was written in another app or in the Lab Chat. Rows are grouped only by day.
 */

/** Where a row's message was written; null when the app isn't known. */
internal val RecentRow.source: HistoryFilter?
    get() = when {
        summary.origin == Origin.Chat -> HistoryFilter.Chat
        summary.hostApp != null -> HistoryFilter.App(summary.hostApp)
        else -> null
    }

/** The row's small source label: the app's name, or "Chat · Weekend plans". */
internal fun RecentRow.sourceLabel(resources: Resources): String? =
    if (summary.origin == Origin.Chat) chatSourceLabel(resources, chat) else appLabel

internal fun chatSourceLabel(resources: Resources, chat: ChatThreadInfo?): String {
    val title = chat?.let { it.title.ifEmpty { it.persona.orEmpty() } }.orEmpty()
    return if (title.isEmpty()) resources.getString(R.string.lab_chat_origin_chat)
    else resources.getString(R.string.lab_history_flat_chat_source, title)
}

/** The source filter above the list: All, one app, or Chat. Saved as [token]. */
internal sealed interface HistoryFilter {
    val token: String
    fun keeps(row: RecentRow): Boolean

    data object All : HistoryFilter {
        override val token = "all"
        override fun keeps(row: RecentRow) = true
    }

    data object Chat : HistoryFilter {
        override val token = "chat"
        override fun keeps(row: RecentRow) = row.summary.origin == Origin.Chat
    }

    data class App(val packageName: String) : HistoryFilter {
        override val token get() = "$APP$packageName"
        override fun keeps(row: RecentRow) = row.summary.origin != Origin.Chat && row.summary.hostApp == packageName
    }

    companion object {
        private const val APP = "app:"

        fun read(token: String?): HistoryFilter = when {
            token == Chat.token -> Chat
            token != null && token.startsWith(APP) && token.length > APP.length -> App(token.removePrefix(APP))
            else -> All
        }
    }
}

/**
 * The chips to offer: All, each app that appears among [rows] (in the order they first appear),
 * then Chat when there are chat messages. [selected] stays offered even once its rows are gone,
 * so the writer can see its empty state and go back.
 */
internal fun filterChoices(rows: List<RecentRow>, selected: HistoryFilter = HistoryFilter.All): List<HistoryFilter> {
    val sources = rows.mapNotNull { it.source }.distinct()
    val apps = (sources.filterIsInstance<HistoryFilter.App>() + listOfNotNull(selected as? HistoryFilter.App)).distinct()
    val chat = HistoryFilter.Chat in sources || selected == HistoryFilter.Chat
    return listOf(HistoryFilter.All) + apps + listOfNotNull(HistoryFilter.Chat.takeIf { chat })
}

/**
 * Lab, Language scope: the rows History shows, newest first: those [filter] keeps, written in
 * [language] (null is All languages). A row whose language wasn't known shows only under All.
 */
internal fun historyRows(rows: List<RecentRow>, filter: HistoryFilter, language: String?): List<RecentRow> =
    rows.filter { filter.keeps(it) && LanguageScope.keeps(it.summary.targetLanguage, language) }

/** An app chip's name: the app's label from any of its rows, else its package name. */
internal fun appName(rows: List<RecentRow>, filter: HistoryFilter.App): String =
    rows.firstOrNull { it.summary.hostApp == filter.packageName && it.appLabel != null }?.appLabel ?: filter.packageName

/** One entry of the list: a day header, or a message under it. */
internal sealed interface HistoryItem {
    val key: String

    data class Day(val date: LocalDate) : HistoryItem {
        override val key get() = "day:$date"
    }

    data class Message(val row: RecentRow) : HistoryItem {
        override val key get() = row.key
    }
}

/** [rows] (newest first) with a header before each day's first message, in [zone]. */
internal fun historyItems(rows: List<RecentRow>, zone: ZoneId = ZoneId.systemDefault()): List<HistoryItem> {
    val items = ArrayList<HistoryItem>(rows.size + 8)
    var day: LocalDate? = null
    rows.forEach { row ->
        val date = Instant.ofEpochMilli(row.summary.startedAt).atZone(zone).toLocalDate()
        if (date != day) {
            items += HistoryItem.Day(date)
            day = date
        }
        items += HistoryItem.Message(row)
    }
    return items
}

/** "Today", "Yesterday", then a short date ("Nov 3", or "Nov 3, 2025" in another year). */
internal fun dayLabel(resources: Resources, date: LocalDate, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (date) {
        today -> resources.getString(R.string.lab_history_flat_today)
        today.minusDays(1) -> resources.getString(R.string.lab_history_flat_yesterday)
        else -> shortDate(date.atStartOfDay(zone).toInstant().toEpochMilli(), now, zone, resources.uiLocale)
    }
}
