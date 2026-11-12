package com.evanaronson.linguize

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.words.DeckCoverage
import com.evanaronson.linguize.core.words.WordIndex
import com.evanaronson.linguize.data.vocabgaps.BundledDecks
import com.evanaronson.linguize.data.vocabgaps.VocabGapsStore
import com.evanaronson.linguize.data.vocabgaps.VocabSelection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset

class DeckLibraryTest {
    @get:Rule val folder = TemporaryFolder()

    private val corpus = mutableListOf(
        Utterance("check:1", "check", "es", "Fue un día en casa", at = 1_704_067_200_000L),
        Utterance("chat:2", "chat", "es", "Tengo que hablar con mi madre", at = 1_706_745_600_000L),
    )
    private val store by lazy { VocabGapsStore(folder.newFolder("lab_vocab_gaps")) }
    private val library by lazy {
        DeckLibrary(
            lemmatizer = { Lemmatizer.Identity },
            store = store,
            bundled = BundledDecks { File("src/main/assets/$it").readText() },
        )
    }

    private fun coverage(deckId: String = "bundled-es", language: String = "es"): DeckCoverage = runBlocking {
        val words = WordIndex.build(corpus, Lemmatizer.Identity, zone = ZoneOffset.UTC).language(language)!!
        DeckCoverage.build(words, library.deck(deckId)!!, Lemmatizer.Identity)
    }

    @Test
    fun bundledSpanishCountsTheWritersWords() {
        val coverage = coverage()
        val used = coverage.used
        assertTrue(used.toString(), "ser/v" in used && "día/n" in used && "casa/n" in used && "tener/v" in used)
        assertEquals(listOf(100, 500, 1000, 2000, 5000), coverage.milestones.map { it.top })
        assertTrue(coverage.deck.hasLexemes)
    }

    @Test
    fun deletingInputLowersTheNumbers() {
        val before = coverage()
        assertTrue("hablar/v" in before.used)
        corpus.removeAt(1)
        val after = coverage()
        assertTrue("hablar/v" !in after.used)
        assertTrue(after.milestones.last().used < before.milestones.last().used)
    }

    @Test
    fun theLemmaViewCollapsesLexemes() = runBlocking {
        val deck = library.deck("bundled-es")!!
        val lemmas = deck.byLemma()
        assertTrue(lemmas.size <= deck.size)
        assertTrue(lemmas.entries.all { it.pos == null })
        val words = WordIndex.build(corpus, Lemmatizer.Identity, zone = ZoneOffset.UTC).language("es")!!
        assertTrue("ser" in DeckCoverage.build(words, lemmas, Lemmatizer.Identity).used)
    }

    @Test
    fun decksAreReadOnceAndKept() = runBlocking {
        assertSame(library.deck("bundled-es"), library.deck("bundled-es"))
        assertNull(library.deck("bundled-xx"))
    }

    @Test
    fun catalanFormsStayFormsWithoutMorphology() = runBlocking {
        corpus += Utterance("check:3", "check", "ca", "Vaig a casa", at = 1_706_745_600_000L)
        val decks = library.decks("ca")
        assertTrue(decks.single().formsOnly)
        val coverage = coverage("bundled-ca", "ca")
        assertTrue(coverage.deck.formsOnly)
        assertTrue("casa" in coverage.used)
        assertTrue("anar" !in coverage.used) // forms only: "vaig" counts toward "vaig"
        assertEquals(listOf("es", "ca"), library.languages())
    }

    @Test
    fun importedDecksCanBeListedUsedAndDeleted() = runBlocking {
        val outcome = library.import("palabra\tgloss\ncasa\thouse\nperro\tdog\n", "Mine", "es") as ImportOutcome.Imported
        assertEquals(2, outcome.deck.size)
        assertEquals(2, library.decks("es").size)
        val coverage = coverage(outcome.deck.id)
        assertEquals(setOf("casa"), coverage.used)
        assertEquals("house", coverage.deck.entries.first().gloss)
        library.delete(outcome.deck.id)
        assertEquals(1, library.decks("es").size)
        assertNull(library.deck(outcome.deck.id))
        assertTrue(library.import("", "Empty", "es") is ImportOutcome.Failed)
    }

    @Test
    fun theSelectionIsKept() = runBlocking {
        library.select(VocabSelection("ca", "bundled-ca"))
        assertEquals("ca", library.selection().language)
    }
}
