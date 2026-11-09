package com.evanaronson.linguize.ui.history

import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/** Lab, Language scope: History shows the selected language, or every language under Linguize (Auto), combined with the source. */
class HistoryLanguageTest {
    private fun row(id: String, language: String?, origin: Origin = Origin.Menu, app: String? = "com.whatsapp") = RecentRow(
        SessionSummary(id, 0, origin, app, "text $id", Outcome.Applied, 1, 0, 1, targetLanguage = language),
        appLabel = null,
    )

    private val rows = listOf(
        row("1", "ca"),
        row("2", "es"),
        row("3", "ca", Origin.Chat, app = null),
        row("4", null),
    )

    @Test fun theCurrentLanguageIsTheDefault() {
        assertEquals(listOf("1", "3"), historyRows(rows, HistoryFilter.All, "ca").map { it.summary.id })
        assertEquals(listOf("2"), historyRows(rows, HistoryFilter.All, "es").map { it.summary.id })
    }

    @Test fun linguizeShowsEverythingIncludingUnknown() {
        assertEquals(listOf("1", "2", "3", "4"), historyRows(rows, HistoryFilter.All, null).map { it.summary.id })
    }

    @Test fun theLanguageCombinesWithTheSource() {
        assertEquals(listOf("3"), historyRows(rows, HistoryFilter.Chat, "ca").map { it.summary.id })
        assertEquals(listOf("1"), historyRows(rows, HistoryFilter.App("com.whatsapp"), "ca").map { it.summary.id })
        assertEquals(listOf("1", "2", "4"), historyRows(rows, HistoryFilter.App("com.whatsapp"), null).map { it.summary.id })
    }
}
