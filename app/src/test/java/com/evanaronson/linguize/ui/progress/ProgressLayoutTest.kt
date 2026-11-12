package com.evanaronson.linguize.ui.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lab, Progress IA: the tab's order. One headline, then the views of the writer's writing, then the Phrasebook (a collection, kept apart and last).
 */
class ProgressLayoutTest {
    @Test fun aChosenLanguageIsHeadlineViewsThenSaved() {
        val layout = progressLayout("ca", listOf("es", "ca"))
        assertEquals(
            listOf(
                ProgressSection.Headline,
                ProgressSection.Views("ca", labelled = false, views = listOf(ProgressView.Words, ProgressView.Conjugations, ProgressView.Patterns)),
                ProgressSection.Saved,
            ),
            layout,
        )
    }

    @Test fun linguizeHasOneHeadlineAndAViewsCardPerLanguage() {
        val layout = progressLayout(null, listOf("es", "ca", "en"))
        assertEquals(ProgressSection.Headline, layout.first())
        val views = layout.filterIsInstance<ProgressSection.Views>()
        assertEquals(listOf("ca", "es", "en"), views.map { it.language })
        assertTrue(views.all { it.labelled })
        // The Phrasebook once, last: its page groups phrases by language itself.
        assertEquals(ProgressSection.Saved, layout.last())
        assertEquals(1, layout.count { it == ProgressSection.Saved })
    }

    @Test fun phrasebookIsNotAViewOfAnyLanguage() {
        listOf("ca", "es", "en").forEach { language ->
            assertTrue(ProgressView.Phrasebook !in progressViews(language))
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

}
