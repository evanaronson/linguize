package com.evanaronson.linguize.data.vocabgaps

import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.morphology.Morphology
import com.evanaronson.linguize.core.morphology.MorphologyLemmatizer
import com.evanaronson.linguize.core.morphology.PartOfSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The bundled frequency lists as evidence for guessed verbs, wired as in `App.morphology`. */
class BundledVerbEvidenceTest {
    private val bundled = BundledDecks { File("src/main/assets/$it").readText() }
    private val morphology = Morphology { bundled.verbEvidence(it) }

    private fun guessedVerbs(word: String, language: String) =
        morphology.verbs(word, language).filter { it.guessed }.map { it.lemma }.toSet()

    @Test
    fun theListsAreRead() {
        val spanish = bundled.verbEvidence("es")!!
        assertTrue(spanish.isVerb("programar") && spanish.hasForm("programaron"))
        val catalan = bundled.verbEvidence("ca")!!
        assertTrue(catalan.hasForm("trigar") && !catalan.isVerb("trigar"))
        assertNull(bundled.verbEvidence("fr"))
    }

    @Test
    fun femininePluralsAreNotVerbs() {
        listOf("maneres", "cadires", "fronteres", "carreteres", "màscares", "teories", "banderes").forEach {
            assertEquals(it, emptySet<String>(), guessedVerbs(it, "ca"))
        }
        listOf("cámaras", "lámparas", "máscaras", "préstamos", "extremos").forEach {
            assertEquals(it, emptySet<String>(), guessedVerbs(it, "es"))
        }
        assertEquals(setOf(Lexeme("manera", Pos.Noun)), MorphologyLemmatizer(morphology).lexemes("maneres", "ca"))
    }

    @Test
    fun verbsTheListsAttestAreStillGuessed() {
        assertEquals(setOf("trigar"), guessedVerbs("trigarem", "ca"))
        assertEquals(setOf("dirigir"), guessedVerbs("dirigeixen", "ca"))
        assertEquals(setOf("penjar"), guessedVerbs("penjaran", "ca"))
        assertEquals(setOf("programar"), guessedVerbs("programaron", "es"))
        assertEquals(setOf("bloquear"), guessedVerbs("bloquearon", "es"))
        assertTrue(morphology.verbs("editaron", "es").any { it.lemma == "editar" && it.guessed && it.pos == PartOfSpeech.Verb })
    }
}
