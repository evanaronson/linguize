package com.evanaronson.linguize.core.language

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lab, Composer language: the composer's placeholder per language, and the keyboard's hint tag. */
class KeyboardLanguageTest {
    private val catalog = LanguageCatalog.parse(File("src/main/assets/lab_languages.json").readText())

    @Test fun thePlaceholderIsInTheConversationsLanguageInformally() {
        assertEquals("Escriu un missatge", KeyboardLanguage.composerPlaceholder("ca"))
        assertEquals("Escribe un mensaje", KeyboardLanguage.composerPlaceholder("es"))
        assertEquals("Écris un message", KeyboardLanguage.composerPlaceholder("fr"))
    }

    @Test fun aRegionFindsItsLanguageOrItsOwnWording() {
        assertEquals("Escribe un mensaje", KeyboardLanguage.composerPlaceholder("es-MX"))
        assertEquals("Escriu un missatge", KeyboardLanguage.composerPlaceholder("ca-ES-valencia"))
        assertEquals("Escreve uma mensagem", KeyboardLanguage.composerPlaceholder("pt"))
        assertEquals("Escreva uma mensagem", KeyboardLanguage.composerPlaceholder("pt-BR"))
        assertEquals("Retired codes read as today's", "כתיבת הודעה", KeyboardLanguage.composerPlaceholder("iw"))
    }

    @Test fun anUnlistedLanguageHasNoneSoTheAppsStringShows() {
        assertNull("Occitan isn't bundled", KeyboardLanguage.composerPlaceholder("oc"))
        assertNull("Greenlandic isn't bundled", KeyboardLanguage.composerPlaceholder("kl"))
        assertNull(KeyboardLanguage.composerPlaceholder("xx"))
        assertNull(KeyboardLanguage.composerPlaceholder(null))
        assertNull(KeyboardLanguage.composerPlaceholder(""))
    }

    @Test fun everyBundledPlaceholderIsForACatalogLanguage() {
        val tags = KeyboardLanguage.placeholderTags
        assertTrue("At least the 40 most common languages", tags.size >= 40)
        for (tag in tags) {
            assertTrue(tag, catalog.find(tag) != null)
            assertTrue(tag, KeyboardLanguage.composerPlaceholder(tag)!!.isNotBlank())
        }
        for (code in listOf("ca", "es", "en", "fr", "de", "it", "pt", "nl", "gl", "eu", "ro", "pl", "sv", "da", "no",
            "fi", "cs", "sk", "hu", "el", "tr", "ru", "uk", "ar", "he", "hi", "ja", "ko", "zh", "id", "vi", "th")) {
            assertTrue(code, code in tags)
        }
    }

    @Test fun theHintIsTheLanguageWithNoneUnderAutoDetect() {
        assertEquals("ca", KeyboardLanguage.tag("ca"))
        assertEquals("fr", KeyboardLanguage.tag("FR"))
        assertEquals("he", KeyboardLanguage.tag("iw"))
        assertNull("Auto-detect", KeyboardLanguage.tag(null))
        assertNull(KeyboardLanguage.tag("xx"))
    }

    @Test fun theWritersVarietyAddsItsRegion() {
        assertEquals("es-MX", KeyboardLanguage.tag("es", "Mexican", catalog))
        assertEquals("es-ES", KeyboardLanguage.tag("es", "Peninsular", catalog))
        assertEquals("pt-BR", KeyboardLanguage.tag("pt", "Brazilian Portuguese", catalog))
        assertEquals("ca-ES", KeyboardLanguage.tag("ca", "standard Central Catalan", catalog))
        assertEquals("ca-ES-valencia", KeyboardLanguage.tag("ca", "Valencian", catalog))
        assertEquals("de-AT", KeyboardLanguage.tag("de", "Österreichisch", catalog))
        assertEquals("A match word is a word's start, not anywhere", "en-AU", KeyboardLanguage.tag("en", "Australian", catalog))
        assertEquals("A variety the catalog doesn't know adds nothing", "en", KeyboardLanguage.tag("en", "General", catalog))
        assertEquals("ja", KeyboardLanguage.tag("ja", "Kansai", catalog))
        assertEquals("No catalog, no region", "es", KeyboardLanguage.tag("es", "Mexican"))
    }
}
