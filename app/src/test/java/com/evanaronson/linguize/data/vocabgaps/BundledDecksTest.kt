package com.evanaronson.linguize.data.vocabgaps

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.morphology.Morphology
import com.evanaronson.linguize.core.morphology.MorphologyLemmatizer
import com.evanaronson.linguize.core.vocab.Milestone
import com.evanaronson.linguize.core.vocab.VocabDeck
import com.evanaronson.linguize.core.vocab.VocabLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every bundled deck shows exactly the top 5,000 rows at the lexeme and at the lemma level,
 * with the app's own grouping: without morphology (each Catalan form its own row) and with it.
 */
class BundledDecksTest {
    private val bundled = BundledDecks { File("src/main/assets/$it").readText() }
    private val lemmatizers = listOf(Lemmatizer.Identity, MorphologyLemmatizer(Morphology()))

    private fun check(deck: VocabDeck, level: VocabLevel) {
        val label = "${deck.language} ${deck.level}"
        assertEquals(label, level, deck.level)
        assertEquals(label, BundledDecks.SIZE, deck.size)
        assertEquals(label, (1..BundledDecks.SIZE).toList(), deck.entries.map { it.rank })
        assertEquals(label, deck.size, deck.entries.map { it.key }.toSet().size)
        if (level == VocabLevel.Lemma) assertEquals(label, deck.size, deck.entries.map { it.lemma }.toSet().size)
        val counts = deck.entries.map { it.count ?: 0L }
        assertEquals(label, counts.sortedDescending(), counts)
        assertTrue(label, deck.reserve.all { (it.count ?: 0L) <= counts.last() })
        assertEquals(label, listOf(100, 500, 1_000, 2_000, 5_000), Milestone.tops(deck.size))
        assertEquals(label, 5_000, deck.entries.take(5_000).size)
    }

    @Test
    fun eachBundledDeckShowsTheTop5000AtEachLevel() {
        for (lemmatizer in lemmatizers) {
            for (language in BundledDecks.languages) {
                val deck = bundled.deck(language, lemmatizer)!!
                check(deck, VocabLevel.Lexeme)
                check(deck.byLemma(), VocabLevel.Lemma)
            }
        }
    }

    @Test
    fun catalanFormsGroupIntoLexemesWithMorphology() {
        val deck = bundled.deck("ca", MorphologyLemmatizer(Morphology()))!!
        assertTrue(!deck.formsOnly && deck.hasLexemes)
        val anar = deck.entries.first { it.key == "anar/v" }
        assertTrue(anar.forms.toString(), "vaig" in anar.forms && "anar" in anar.forms)
        // Grouping merged forms, so the shown 5,000 rows came from more than 5,000 forms.
        assertTrue(deck.entries.sumOf { it.forms.size } > BundledDecks.SIZE)
    }

    @Test
    fun withoutMorphologyCatalanShowsTheTop5000Forms() {
        val deck = bundled.deck("ca", Lemmatizer.Identity)!!
        assertTrue(deck.formsOnly)
        val asset = File("src/main/assets/lab_vocab_ca.csv").readLines().drop(1).take(BundledDecks.SIZE).map { it.split(',')[1] }
        assertEquals(asset, deck.entries.map { it.lemma })
    }
}
