package com.evanaronson.linguize.ui.language

import com.evanaronson.linguize.core.language.LanguageCatalog
import com.evanaronson.linguize.core.language.MyLanguages
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lab, lang-selector: what the Languages page lists, and what checking a language does. */
class AddLanguagesTest {
    private val catalog = LanguageCatalog.parse(File("src/main/assets/lab_languages.json").readText())

    private fun kinds(sections: List<AddLanguages.Section>) = sections.map { it.kind }

    @Test fun yoursThenSuggestedThenEveryLanguage() {
        val sections = AddLanguages.sections(catalog, listOf("ca", "es"), listOf("fr", "ca", "de"), "")
        assertEquals(listOf(AddLanguages.Kind.Yours, AddLanguages.Kind.Suggested, AddLanguages.Kind.Every), kinds(sections))
        assertEquals(listOf(AddLanguages.Row("ca", true), AddLanguages.Row("es", true)), sections[0].rows)
        // Suggested leaves out the writer's own languages and keeps History's order.
        assertEquals(listOf("fr", "de"), sections[1].rows.map { it.code })
        assertTrue(sections[1].rows.none { it.checked })
        val every = sections[2].rows
        assertEquals(183, every.size)
        assertEquals("ab", every.first().code) // Abkhazian, A to Z by English name
        assertTrue(every.single { it.code == "ca" }.checked)
        assertTrue(!every.single { it.code == "fr" }.checked)
    }

    @Test fun noSuggestedSectionWhenHistoryHasNothingNew() {
        val sections = AddLanguages.sections(catalog, listOf("ca", "es"), listOf("es"), "")
        assertEquals(listOf(AddLanguages.Kind.Yours, AddLanguages.Kind.Every), kinds(sections))
    }

    @Test fun searchFindsEnglishNativeBrandAndCode() {
        fun found(query: String) = AddLanguages.sections(catalog, listOf("ca"), emptyList(), query).single().let {
            assertEquals(AddLanguages.Kind.Results, it.kind)
            it.rows.map { row -> row.code }
        }
        assertEquals("fr", found("french").first())
        assertEquals("fr", found("Gallicize").first())
        assertEquals("ca", found("catala").first())
        assertEquals("ja", found("日本").first())
        assertEquals("pt", found("pt").first())
        assertEquals(emptyList<String>(), found("zzzz"))
        assertTrue(AddLanguages.sections(catalog, listOf("ca"), emptyList(), "catalan").single().rows.first().checked)
    }

    @Test fun theLastLanguageStaysAndAFullListTakesNoMore() {
        assertEquals(AddLanguages.Toggle.Add, AddLanguages.toggle(listOf("ca"), "fr"))
        assertEquals(AddLanguages.Toggle.Remove, AddLanguages.toggle(listOf("ca", "fr"), "fr"))
        assertEquals(AddLanguages.Toggle.KeepLast, AddLanguages.toggle(listOf("ca"), "ca"))
        val full = catalog.all().take(MyLanguages.MAX).map { it.code }
        assertEquals(AddLanguages.Toggle.Full, AddLanguages.toggle(full, "zu"))
        assertEquals(AddLanguages.Toggle.Remove, AddLanguages.toggle(full, full.first()))
    }
}
