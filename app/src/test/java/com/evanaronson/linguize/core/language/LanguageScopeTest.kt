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

    @Test fun theSelectorListsLinguizeCatalanizeCastilianizeThenOthersInTheData() {
        assertEquals(listOf(null, "ca", "es", "en"), LanguageScope.options(listOf("es", "en"), "es"))
        assertEquals(listOf(null, "ca", "es", "fr"), LanguageScope.options(emptyList(), "fr"))
        assertEquals(listOf(null, "ca", "es"), LanguageScope.options(listOf("auto", ""), null))
    }

    @Test fun languagesInUseAreTheBrandsWithDataThenOthers() {
        assertEquals(listOf("ca", "es", "en"), LanguageScope.inUse(listOf("en", "es", "ca", null)))
        assertEquals(listOf("es"), LanguageScope.inUse(listOf("es")))
        assertEquals(LanguageScope.BRANDS, LanguageScope.inUse(listOf(null)))
    }

    @Test fun scopedViewsKeepOnlyTheirLanguageAndUnknownOnlyUnderLinguize() {
        assertTrue(LanguageScope.keeps("ca", "ca"))
        assertFalse(LanguageScope.keeps("es", "ca"))
        assertFalse(LanguageScope.keeps(null, "ca"))
        assertTrue(LanguageScope.keeps(null, null))
        assertTrue(LanguageScope.keeps("es", null))
    }
}
