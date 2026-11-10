package com.evanaronson.linguize.core.language

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app-wide language catalog: ISO 639-1 with names, brand verbs and flag keys, and its lookups. */
class LanguageCatalogTest {
    private val catalog = LanguageCatalog.parse(File("src/main/assets/lab_languages.json").readText())
    private val all = catalog.all()

    @Test fun coversIso6391WithUniqueTwoLetterCodes() {
        assertEquals(183, all.size)
        assertEquals(all.size, all.map { it.code }.toSet().size)
        all.forEach { assertTrue(it.code, Regex("[a-z]{2}").matches(it.code)) }
    }

    @Test fun everyLanguageHasEnglishNativeAndBrandNames() {
        all.forEach {
            assertTrue(it.code, it.english.isNotBlank())
            assertTrue(it.code, it.native.isNotBlank())
            assertTrue(it.code, it.brand.isNotBlank())
            assertTrue(it.code, it.kind in setOf(CatalogLanguage.LIVING, CatalogLanguage.CLASSICAL, CatalogLanguage.CONSTRUCTED))
        }
    }

    @Test fun brandNamesAreUniqueCapitalisedIzeVerbs() {
        all.forEach {
            assertTrue(it.brand, it.brand.endsWith("ize"))
            assertTrue(it.brand, it.brand.first().isUpperCase())
            assertFalse(it.brand, it.brand.contains(' '))
        }
        assertEquals(all.size, all.map { it.brand.lowercase() }.toSet().size)
    }

    @Test fun keepsTheProductsOwnNamesAndTheEstablishedWords() {
        assertEquals("Catalanize", catalog.brandName("ca"))
        assertEquals("Castilianize", catalog.brandName("es"))
        assertEquals("Anglicize", catalog.brandName("en"))
        assertEquals("Gallicize", catalog.brandName("fr"))
        assertEquals("Hellenize", catalog.brandName("el"))
        assertNull(catalog.brandName("xx"))
        assertNull(catalog.brandName(null))
    }

    @Test fun avoidsWordsThatNameForcedAssimilation() {
        val avoided = setOf("Russify", "Magyarize", "Arabize", "Turkify", "Bulgarize", "Norwegianize", "Vietnamize", "Burmanize")
        all.forEach { assertFalse(it.brand, it.brand in avoided) }
    }

    @Test fun isSortedByEnglishName() {
        val names = all.map { it.english.lowercase() }
        assertEquals("Abkhazian", all.first().english)
        assertTrue(names.indexOf("catalan") < names.indexOf("chinese"))
        assertEquals("Zulu", all.last().english)
    }

    @Test fun macroAndVarietyReferencesPointInsideTheCatalog() {
        all.mapNotNull { it.macro }.forEach { assertTrue(it, catalog.find(it)?.macrolanguage == true) }
        all.flatMap { it.varieties }.forEach { assertTrue(it.name, it.match.isNotEmpty() && it.flag.isNotBlank()) }
    }

    @Test fun findsCodesCaseInsensitivelyTagsAndRetiredCodes() {
        assertEquals("Catalan", catalog.find("CA")?.english)
        assertEquals("pt", catalog.find("pt-BR")?.code)
        assertEquals("zh", catalog.find("zh_Hant")?.code)
        assertEquals("he", catalog.find("iw")?.code)
        assertEquals("tl", catalog.find("fil")?.code)
        assertNull(catalog.find(""))
        assertNull(catalog.find("qq"))
    }

    @Test fun searchMatchesNamesBrandsAndCodesIgnoringCaseAndAccents() {
        assertEquals("ca", catalog.search("catala").first().code)
        assertEquals("ca", catalog.search("CATALÀ").first().code)
        assertEquals("de", catalog.search("deutsch").single().code)
        assertEquals("en", catalog.search("anglicize").single().code)
        assertEquals("fa", catalog.search("farsi").single().code)
        assertEquals("mi", catalog.search("maori").single().code)
        assertEquals("ja", catalog.search("日本").single().code)
        assertEquals("es", catalog.search("es").first().code)
        assertTrue(catalog.search("castilian").any { it.code == "es" })
        assertEquals(all, catalog.search("  "))
        assertTrue(catalog.search("zzzz").isEmpty())
    }

    @Test fun searchPutsNamesStartingWithTheQueryFirst() {
        assertEquals("de", catalog.search("ger").first().code)
        // "Ndebele" in the middle of "South Ndebele" counts as a start; "-ize" endings only as contained.
        assertEquals(setOf("nd", "nr"), catalog.search("ndebele").map { it.code }.toSet())
        assertEquals("pt", catalog.search("portug").first().code)
    }

    @Test fun flagKeysFollowTheLanguageAndItsVariety() {
        assertEquals("es-ct", catalog.flagKey("ca"))
        assertEquals("es", catalog.flagKey("es"))
        assertEquals("es", catalog.flagKey("es", "Peninsular"))
        assertEquals("mx", catalog.flagKey("es", "Mexican"))
        assertEquals("ar", catalog.flagKey("es", "Rioplatense"))
        assertEquals("co", catalog.flagKey("es", "Colombian Spanish"))
        assertEquals("es", catalog.flagKey("es", "Andalusian"))
        assertEquals("br", catalog.flagKey("pt", "Brazilian"))
        assertEquals("pt", catalog.flagKey("pt"))
        assertNull(catalog.flagKey("ae"))
        assertNull(catalog.flagKey("xx"))
    }

    @Test fun mostLanguagesHaveAFlag() {
        assertTrue(all.count { it.flag != null } >= 170)
    }

    @Test fun foldStripsAccentsAndCase() {
        assertEquals("catala", LanguageCatalog.fold(" Català "))
        assertEquals("volapukize", LanguageCatalog.fold("Volapükize"))
    }
}
