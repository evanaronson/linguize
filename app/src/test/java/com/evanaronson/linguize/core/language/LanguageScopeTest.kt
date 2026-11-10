package com.evanaronson.linguize.core.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageScopeTest {
    @Test fun linguizeAutoIsTheDefaultAndAStoredLanguageIsKeptAsItIs() {
        // Nothing stored yet: Linguize (Auto).
        assertNull(LanguageScope.current(null))
        assertNull(LanguageScope.current(LanguageScope.AUTO))
        // A language switched to before Auto existed migrates as it is.
        assertEquals("ca", LanguageScope.current("ca"))
        // Anything that isn't a language code reads as Auto.
        assertNull(LanguageScope.current("Catalan"))
        assertEquals(LanguageScope.AUTO, LanguageScope.stored(null))
        assertEquals("es", LanguageScope.stored("es"))
    }

    @Test fun theSelectorListsLinguizeThenTheWritersLanguagesThenTheCurrentOne() {
        assertEquals(listOf(null, "ca", "es"), LanguageScope.options(listOf("ca", "es"), "es"))
        assertEquals(listOf(null, "ca", "es", "fr"), LanguageScope.options(listOf("ca", "es"), "fr"))
        assertEquals(listOf(null, "fr", "de"), LanguageScope.options(listOf("fr", "de"), null))
        assertEquals(listOf(null, "ca"), LanguageScope.options(listOf("ca", "auto", ""), null))
    }

    @Test fun languagesFoundButNotAddedAreSuggested() {
        assertEquals(listOf("en", "fr"), LanguageScope.suggested(listOf("es", "en", null, "fr", "en"), listOf("ca", "es")))
    }

    @Test fun languagesInUseAreTheWritersWithDataThenOthers() {
        assertEquals(listOf("ca", "es", "en"), LanguageScope.inUse(listOf("en", "es", "ca", null)))
        assertEquals(listOf("es"), LanguageScope.inUse(listOf("es")))
        assertEquals(LanguageScope.DEFAULT_LANGUAGES, LanguageScope.inUse(listOf(null)))
        // A writer of French and German: theirs first, in their order, then others found.
        assertEquals(listOf("fr", "de"), LanguageScope.inUse(emptyList(), listOf("fr", "de")))
        assertEquals(listOf("de", "fr", "es"), LanguageScope.inUse(listOf("es", "fr", "de"), listOf("de", "fr")))
    }

    @Test fun scopedViewsKeepOnlyTheirLanguageAndUnknownOnlyUnderLinguize() {
        assertTrue(LanguageScope.keeps("ca", "ca"))
        assertFalse(LanguageScope.keeps("es", "ca"))
        assertFalse(LanguageScope.keeps(null, "ca"))
        assertTrue(LanguageScope.keeps(null, null))
        assertTrue(LanguageScope.keeps("es", null))
    }
}
