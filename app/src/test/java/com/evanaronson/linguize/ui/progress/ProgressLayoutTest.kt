package com.evanaronson.linguize.ui.progress

import com.evanaronson.linguize.core.practice.Deck
import com.evanaronson.linguize.core.practice.Grade
import com.evanaronson.linguize.core.practice.PracticeItem
import com.evanaronson.linguize.core.practice.PracticeProgress
import com.evanaronson.linguize.ui.practice.PracticeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lab, Progress IA: the tab's order. One headline, then Practice (the thing to do), then the
 * views of the writer's writing, then the Phrasebook (a collection, kept apart and last).
 */
class ProgressLayoutTest {
    @Test fun aChosenLanguageIsHeadlinePracticeViewsThenSaved() {
        val layout = progressLayout("ca", listOf("es", "ca"))
        assertEquals(
            listOf(
                ProgressSection.Headline,
                ProgressSection.Practice(listOf("ca"), labelled = false),
                ProgressSection.Views("ca", labelled = false, views = listOf(ProgressView.Words, ProgressView.Conjugations, ProgressView.Patterns)),
                ProgressSection.Saved,
            ),
            layout,
        )
    }

    @Test fun linguizeHasOneHeadlineOnePracticeAndAViewsCardPerLanguage() {
        val layout = progressLayout(null, listOf("es", "ca", "en"))
        assertEquals(ProgressSection.Headline, layout.first())
        assertEquals(ProgressSection.Practice(listOf("ca", "es", "en"), labelled = true), layout[1])
        val views = layout.filterIsInstance<ProgressSection.Views>()
        assertEquals(listOf("ca", "es", "en"), views.map { it.language })
        assertTrue(views.all { it.labelled })
        // Every language's practice is together, above every view.
        assertEquals(1, layout.count { it is ProgressSection.Practice })
        assertTrue(layout.indexOfFirst { it is ProgressSection.Views } > layout.indexOfFirst { it is ProgressSection.Practice })
        // The Phrasebook once, last: its page groups phrases by language itself.
        assertEquals(ProgressSection.Saved, layout.last())
        assertEquals(1, layout.count { it == ProgressSection.Saved })
    }

    @Test fun phrasebookIsNotAViewOfAnyLanguage() {
        listOf("ca", "es", "en").forEach { language ->
            assertTrue(ProgressView.Phrasebook !in progressViews(language))
            assertTrue(ProgressView.Practice !in progressViews(language))
            assertTrue(ProgressView.Time !in progressViews(language))
        }
    }

    @Test fun conjugationsOnlyWhereThereIsMorphology() {
        assertEquals(listOf(ProgressView.Words, ProgressView.Conjugations, ProgressView.Patterns), progressViews("es"))
        // Chosen or under Linguize, a language without morphology has no row that only says so.
        assertEquals(listOf(ProgressView.Words, ProgressView.Patterns), progressViews("en"))
        val chosen = progressLayout("en", emptyList()).filterIsInstance<ProgressSection.Views>().single()
        assertEquals(listOf(ProgressView.Words, ProgressView.Patterns), chosen.views)
    }

    @Test fun nothingYetStillShowsEveryPlace() {
        val layout = progressLayout(null, emptyList())
        assertEquals(listOf("ca", "es"), layout.filterIsInstance<ProgressSection.Views>().map { it.language })
        assertEquals(ProgressSection.Headline, layout.first())
        assertEquals(ProgressSection.Saved, layout.last())
    }

    @Test fun eachLanguageRowSaysWhenItsNextPracticeIs() {
        val items = listOf(
            PracticeItem("a", "check-a", 1, "es", "Nos vemos despues.", 10, 17, "después"),
            PracticeItem("b", "check-b", 1, "ca", "Fins dema.", 5, 9, "demà"),
        )
        val fresh = PracticeState(items = items, progress = PracticeProgress(), now = 10, today = 0)
        // Nothing practised yet: no next review to name.
        assertNull(fresh.nextDueIn("es"))
        // Spanish practised: its row names its next review; Catalan's still has none.
        val practised = fresh.copy(progress = Deck.record(PracticeProgress(), "a", Grade.Good, 10, 0))
        assertTrue((practised.nextDueIn("es") ?: 0) > 10)
        assertNull(practised.nextDueIn("ca"))
        // Whatever the page's scope, as under Linguize.
        assertEquals(practised.nextDueIn("es"), practised.copy(language = "ca").nextDueIn("es"))
        assertNull(practised.nextDueIn("en"))
    }
}
