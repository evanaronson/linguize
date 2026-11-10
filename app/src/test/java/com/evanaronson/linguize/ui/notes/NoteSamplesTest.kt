package com.evanaronson.linguize.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSamplesTest {
    @Test fun theDefaultLanguagesKeepTheCatalanSample() {
        assertEquals("Bon dia! Com estas amb la pluja?", NoteSamples.forLanguages(listOf("ca", "es")))
    }

    @Test fun theFirstLanguageWithASampleWins() {
        assertEquals(NoteSamples.byLanguage["fr"], NoteSamples.forLanguages(listOf("ja", "fr", "es")))
    }

    @Test fun languagesWithoutASampleFallBackToEnglish() {
        assertEquals(NoteSamples.byLanguage["en"], NoteSamples.forLanguages(listOf("ja", "ko")))
        assertEquals(NoteSamples.byLanguage["en"], NoteSamples.forLanguages(emptyList()))
    }

    @Test fun everySampleIsOneShortLine() {
        NoteSamples.byLanguage.values.forEach { assertTrue(it, it.isNotBlank() && it.length < 80 && '\n' !in it) }
    }
}
