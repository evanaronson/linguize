package com.evanaronson.linguize

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.vocab.VocabLevel
import com.evanaronson.linguize.data.vocabgaps.BundledDecks
import com.evanaronson.linguize.data.vocabgaps.VocabGapsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset

class VocabGapsLabTest {
    @get:Rule val folder = TemporaryFolder()

    private val corpus = mutableListOf(
        Utterance("check:1", "check", "es", "Fue un día en casa", at = 1_704_067_200_000L),
        Utterance("chat:2", "chat", "es", "Tengo que hablar con mi madre", at = 1_706_745_600_000L),
    )
    private val store by lazy { VocabGapsStore(folder.newFolder("lab_vocab_gaps")) }
    private val lab by lazy {
        VocabGapsLab(
            utterances = { corpus.toList() },
            lemmatizer = { Lemmatizer.Identity },
            store = store,
            bundled = BundledDecks { File("src/main/assets/$it").readText() },
            zone = { ZoneOffset.UTC },
        )
    }

    private fun load(level: VocabLevel = VocabLevel.Lexeme) = runBlocking { lab.load("bundled-es", level, lab.corpus()) }!!

    @Test
    fun bundledSpanishCountsTheWritersWordsAndSavesTheReport() {
        val view = load()
        val used = view.report.used.keys
        assertTrue(used.toString(), "ser/v" in used && "día/n" in used && "casa/n" in used && "tener/v" in used)
        assertEquals(listOf(100, 500, 1000, 2000, 5000), view.report.milestones.map { it.top })
        assertNotNull(store.report("bundled-es", VocabLevel.Lexeme, view.report.fingerprint))
        assertTrue(view.hasLexemes)
    }

    @Test
    fun deletingInputRebuildsAndLowersTheNumbers() {
        val before = load()
        assertTrue("hablar/v" in before.report.used)
        corpus.removeAt(1)
        val after = load()
        assertTrue(after.report.fingerprint != before.report.fingerprint)
        assertTrue("hablar/v" !in after.report.used)
        assertTrue(after.report.milestones.last().used < before.report.milestones.last().used)
        assertNull(store.report("bundled-es", VocabLevel.Lexeme, before.report.fingerprint))
    }

    @Test
    fun lemmaViewCollapsesLexemes() {
        val lexemes = load(VocabLevel.Lexeme)
        val lemmas = load(VocabLevel.Lemma)
        assertEquals(VocabLevel.Lemma, lemmas.report.level)
        assertTrue(lemmas.deck.size <= lexemes.deck.size)
        assertTrue(lemmas.deck.entries.all { it.pos == null })
        assertTrue("ser" in lemmas.report.used)
    }

    @Test
    fun catalanFormsStayFormsWithoutMorphology() = runBlocking {
        corpus += Utterance("check:3", "check", "ca", "Vaig a casa", at = 1_706_745_600_000L)
        val decks = lab.decks("ca")
        assertTrue(decks.single().formsOnly)
        val view = lab.load("bundled-ca", VocabLevel.Lexeme, lab.corpus())!!
        assertTrue(view.deck.formsOnly)
        assertTrue("casa" in view.report.used)
        assertTrue("anar" !in view.report.used) // forms only: "vaig" counts toward "vaig"
        assertEquals(listOf("es", "ca"), lab.languages(lab.corpus()))
    }

    @Test
    fun importedDecksCanBeListedAndDeleted() = runBlocking {
        val outcome = lab.import("palabra\tgloss\ncasa\thouse\nperro\tdog\n", "Mine", "es") as ImportOutcome.Imported
        assertEquals(2, outcome.deck.size)
        assertEquals(2, lab.decks("es").size)
        val view = lab.load(outcome.deck.id, VocabLevel.Lexeme, lab.corpus())!!
        assertEquals(setOf("casa"), view.report.used.keys)
        assertEquals("house", view.deck.entries.first().gloss)
        lab.delete(outcome.deck.id)
        assertEquals(1, lab.decks("es").size)
        assertNull(lab.load(outcome.deck.id, VocabLevel.Lexeme, lab.corpus()))
        assertTrue(lab.import("", "Empty", "es") is ImportOutcome.Failed)
    }
}
