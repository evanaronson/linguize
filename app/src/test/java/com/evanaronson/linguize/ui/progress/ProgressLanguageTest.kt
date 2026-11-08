package com.evanaronson.linguize.ui.progress

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressLanguageTest {
    @Test fun theSwitchCoversCatalanAndSpanish() {
        assertEquals(listOf("ca", "es"), PROGRESS_LANGUAGES)
    }

    @Test fun aChosenLanguageWins() {
        assertEquals("es", progressLanguage("es", listOf("ca" to 300, "es" to 10), fallback = "ca"))
    }

    @Test fun withoutAChoiceTheLanguageWithMostWordsIsShown() {
        assertEquals("ca", progressLanguage(null, listOf("ca" to 300, "es" to 10), fallback = "es"))
        // Text in other or unknown languages doesn't count; neither does a language with no words.
        assertEquals("es", progressLanguage(null, listOf(null to 50, "en" to 40, "ca" to 0, "es" to 3), fallback = "ca"))
    }

    @Test fun withNoWordsTheFallbackIsShown() {
        assertEquals("es", progressLanguage(null, emptyList(), fallback = "es"))
        assertEquals("ca", progressLanguage("fr", emptyList(), fallback = "ca"))
        assertEquals("ca", progressLanguage(null, emptyList(), fallback = "fr"))
    }
}
