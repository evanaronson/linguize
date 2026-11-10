package com.evanaronson.linguize.core.vocab

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabDeckTest {
    private val forms = listOf(
        DeckEntry(1, "que", count = 100),
        DeckEntry(2, "vaig", count = 40),
        DeckEntry(3, "anar", count = 30),
        DeckEntry(4, "va", count = 50),
    )

    @Test
    fun withoutMorphologyEachFormIsItsOwnEntry() {
        val deck = VocabDeck.fromForms("ca", "", "ca", forms, true) { Lemmatizer.Identity.lexemes(it, "ca") }
        assertTrue(deck.formsOnly)
        assertEquals(listOf("que", "va", "vaig", "anar"), deck.entries.map { it.lemma })
        assertEquals((1..4).toList(), deck.entries.map { it.rank })
    }

    @Test
    fun morphologyGroupsFormsIntoLexemesRankedBySummedCount() {
        val morphology = Lemmatizer { word, _ ->
            when (word) {
                "vaig", "va", "anar" -> setOf(Lexeme("anar", Pos.Verb))
                else -> setOf(Lexeme(word, Pos.Conjunction))
            }
        }
        val deck = VocabDeck.fromForms("ca", "", "ca", forms, true) { morphology.lexemes(it, "ca") }
        assertFalse(deck.formsOnly)
        assertEquals(listOf("anar/v", "que/conj"), deck.entries.map { it.key })
        assertEquals(120L, deck.entries.first().count)
        assertEquals(listOf("vaig", "anar", "va"), deck.entries.first().forms)
    }

    @Test
    fun suggestionsTakeTheTopUnusedByPartOfSpeech() {
        val deck = VocabDeck(
            "es", "", "es",
            listOf(
                DeckEntry(1, "ser", Pos.Verb.token), DeckEntry(2, "casa", Pos.Noun.token),
                DeckEntry(3, "tener", Pos.Verb.token), DeckEntry(4, "de", Pos.Preposition.token),
                DeckEntry(5, "grande", Pos.Adjective.token),
            ),
            true,
        )
        val report = VocabAnalyser(Lemmatizer.Identity).analyse(deck, emptyList()).copy(
            used = mapOf("ser/v" to WordUse(1, 0, emptyList())),
        )
        val next = VocabSuggestions.next(deck, report.used.keys)
        assertEquals(listOf("v", "n", "adj", "other"), next.map { it.group })
        assertEquals(listOf("tener"), next.first().entries.map { it.lemma })
        assertEquals(listOf("casa", "tener", "grande"), VocabSuggestions.targets(deck, report).map { it.lemma })
    }
}
