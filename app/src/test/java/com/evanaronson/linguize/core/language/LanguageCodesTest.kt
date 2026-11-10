package com.evanaronson.linguize.core.language

import com.evanaronson.linguize.core.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Lab, language-generic: any ISO 639-1 code is a language, named by the platform until the catalog names it. */
class LanguageCodesTest {
    @Test fun everyIsoLanguageIsALanguageAndCatalanAndSpanishKeepTheirVarieties() {
        assertTrue(LanguageCodes.all.size > 150)
        assertEquals(Language("fr", "French"), Language.forCode("fr"))
        assertEquals("Japanese", Language.forCode("ja")!!.name)
        assertNull(Language.forCode("fr")!!.variety)
        assertEquals(Language.Catalan, Language.forCode("ca"))
        assertEquals(Language.Spanish, Language.forCode("es"))
        // Not languages: names, three letters, made-up codes, Auto.
        assertNull(Language.forCode("French"))
        assertNull(Language.forCode("fra"))
        assertNull(Language.forCode("xx"))
        assertNull(Language.forCode("auto"))
        assertNull(Language.forCode(null))
    }

    @Test fun codesAreStoredLowercaseInTheirCurrentForm() {
        assertEquals("fr", LanguageCodes.normalize(" FR "))
        assertEquals("he", LanguageCodes.normalize("iw"))
        assertEquals("id", LanguageCodes.normalize("in"))
        assertTrue(LanguageCodes.isLanguage("he"))
        assertFalse(LanguageCodes.isLanguage("iw"))
        assertFalse(LanguageCodes.isLanguage("FR"))
    }

    @Test fun historyStillReadsTheEnglishNamesItKeptBeforeCodes() {
        assertEquals(Language.Spanish, Language.read("Spanish"))
        assertEquals(Language.Catalan, Language.read("ca"))
        assertEquals(Language("de", "German"), Language.read("de"))
        assertNull(Language.read("Klingon"))
    }

    @Test fun aModelsEnglishNameReadsAsItsCode() {
        assertEquals("fr", LanguageCodes.fromEnglishName("French"))
        assertEquals("es", LanguageCodes.fromEnglishName("Spanish (Mexican)"))
        assertEquals("ca", LanguageCodes.fromEnglishName("Catalan, Valencian"))
        assertEquals("pt", LanguageCodes.fromEnglishName("pt"))
        assertNull(LanguageCodes.fromEnglishName("Elvish"))
        assertNull(LanguageCodes.fromEnglishName(" "))
    }

    @Test fun namesComeFromTheHookWhichTheCatalogCanReplace() {
        assertEquals("Francés", LocaleLanguageNames.name("fr", Locale.forLanguageTag("es")))
        assertEquals("French", Languages.names.name("fr", Locale.ENGLISH))
        assertNull(Languages.names.brand("fr", Locale.ENGLISH))
        val before = Languages.names
        try {
            Languages.names = object : LanguageNames by LocaleLanguageNames {
                override fun brand(code: String, displayIn: Locale) = if (code == "fr") "Gallicize" else null
            }
            assertEquals("Gallicize", Languages.names.brand("fr", Locale.ENGLISH))
        } finally {
            Languages.names = before
        }
    }
}
