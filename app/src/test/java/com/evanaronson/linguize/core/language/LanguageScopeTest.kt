package com.evanaronson.linguize.core.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageScopeTest {
    @Test fun theChosenLanguageWinsOverTheDefault() {
        assertEquals("ca", LanguageScope.current("ca", "es"))
        assertEquals("es", LanguageScope.current(null, "es"))
        // Anything that isn't a language code reads as no choice.
        assertEquals("es", LanguageScope.current("Catalan", "es"))
    }

    @Test fun theSwitchListsProfileLanguagesThenLanguagesInTheData() {
        assertEquals(listOf("ca", "es", "en"), LanguageScope.options(listOf("ca", "es"), listOf("es", "en"), "es"))
        assertEquals(listOf("ca", "es", "fr"), LanguageScope.options(listOf("ca", "es"), emptyList(), "fr"))
        assertEquals(listOf("ca"), LanguageScope.options(listOf("ca"), listOf("auto", ""), null))
    }

    @Test fun scopedViewsKeepOnlyTheirLanguageAndUnknownOnlyUnderAll() {
        assertTrue(LanguageScope.keeps("ca", "ca"))
        assertFalse(LanguageScope.keeps("es", "ca"))
        assertFalse(LanguageScope.keeps(null, "ca"))
        assertTrue(LanguageScope.keeps(null, null))
        assertTrue(LanguageScope.keeps("es", null))
    }
}
