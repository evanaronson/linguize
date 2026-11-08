package com.evanaronson.linguize.core.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordFoldingTest {
    @Test
    fun tokensSplitApostrophesAndHyphensButKeepLlWithMiddleDot() {
        val tokens = WordFolding.tokens("L'home vol fer-ho. Col·legi, 3 cops!")
        assertEquals(listOf("L", "home", "vol", "fer", "ho", "Col·legi", "cops"), tokens.map { it.text })
        assertEquals(listOf(true, false, false, false, false, true, false), tokens.map { it.sentenceStart })
        val home = tokens[1]
        assertEquals("home", "L'home vol fer-ho. Col·legi, 3 cops!".substring(home.start, home.end))
    }

    @Test
    fun nominalFoldsPluralsAndGender() {
        assertTrue("ciudad" in WordFolding.nominal("ciudades", "es"))
        assertTrue("vez" in WordFolding.nominal("veces", "es"))
        assertTrue("bueno" in WordFolding.nominal("buenas", "es"))
        assertTrue("casa" in WordFolding.nominal("cases", "ca"))
        assertTrue("petit" in WordFolding.nominal("petites", "ca"))
    }

    @Test
    fun verbalProposesInfinitives() {
        assertTrue("hablar" in WordFolding.verbal("hablamos", "es"))
        assertTrue("buscar" in WordFolding.verbal("busqué", "es"))
        assertTrue("decir" in WordFolding.verbal("decirle", "es"))
        assertTrue("parlar" in WordFolding.verbal("parlem", "ca"))
    }

    @Test
    fun irregularFormsAndAccents() {
        assertEquals(setOf("ser", "ir"), WordFolding.irregular("fuimos", "es"))
        assertEquals(setOf("anar"), WordFolding.irregular("vaig", "ca"))
        assertEquals("telefono", WordFolding.stripAccents("teléfono"))
    }
}
