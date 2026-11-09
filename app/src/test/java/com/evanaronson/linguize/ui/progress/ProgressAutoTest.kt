package com.evanaronson.linguize.ui.progress

import com.evanaronson.linguize.core.practice.PracticeItem
import com.evanaronson.linguize.core.time.TimeRecord
import com.evanaronson.linguize.core.time.TimeReport
import com.evanaronson.linguize.ui.practice.PracticeState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

/**
 * Lab, Consistency: Progress under Linguize (Auto). Sections that are inherently per language
 * show one block per language in use; Time adds up across languages, with a breakdown.
 */
class ProgressAutoTest {
    private fun item(id: String, language: String?) =
        PracticeItem(id, "check-$id", 1, language, "Nos vemos despues.", 10, 17, "después")

    @Test fun aChosenLanguageShowsOnlyItsBlock() {
        assertEquals(listOf("ca"), progressLanguages("ca", listOf("es", "ca", "en")))
        // Even with nothing in it yet.
        assertEquals(listOf("es"), progressLanguages("es", emptyList()))
    }

    @Test fun linguizeShowsABlockPerLanguageInUseBrandsFirst() {
        assertEquals(listOf("ca", "es", "en", "fr"), progressLanguages(null, listOf("fr", "es", null, "en", "ca", "es")))
        assertEquals(listOf("es"), progressLanguages(null, listOf("es", null)))
        // Nothing yet: the brand languages, so every view stays one tap away.
        assertEquals(listOf("ca", "es"), progressLanguages(null, emptyList()))
    }

    @Test fun eachBlockCountsItsOwnDueItemsWhateverThePracticeScope() {
        val items = listOf(item("a", "es"), item("b", "es"), item("c", "ca"), item("d", null))
        val state = PracticeState(items = items, now = 10, today = 0)
        assertEquals(2, state.dueIn("es"))
        assertEquals(1, state.dueIn("ca"))
        assertEquals(0, state.dueIn("en"))
        // A page opened from the Catalan block scopes Practice; the blocks still count their own.
        val scoped = state.copy(language = "ca")
        assertEquals(1, scoped.due)
        assertEquals(2, scoped.dueIn("es"))
        // Linguize's own Practice (no scope) counts everything, unknown included.
        assertEquals(4, state.due)
    }

    @Test fun timeTotalsAddUpAcrossLanguagesWithABreakdown() {
        val now = 1_800_000_000_000L
        val records = listOf(
            TimeRecord("a", "chat", "es", 60_000, 10_000, 20, 1, 0, now),
            TimeRecord("b", "chat", "ca", 30_000, 0, 10, 0, 0, now),
        )
        val all = TimeReport.of(records, now, ZoneOffset.UTC, language = null)
        assertEquals(100_000L, all.allTime.totalMs)
        assertEquals(setOf("ca", "es"), all.languages.map { it.language }.toSet())
    }
}
