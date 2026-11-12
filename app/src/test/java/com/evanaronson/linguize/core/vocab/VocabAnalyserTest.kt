package com.evanaronson.linguize.core.vocab

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.corpus.Span
import com.evanaronson.linguize.core.corpus.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class VocabAnalyserTest {
    private val utc = ZoneOffset.UTC

    private fun entry(rank: Int, lemma: String, pos: Pos? = null, vararg forms: String) =
        DeckEntry(rank, lemma, pos?.token, forms = forms.toList())

    private val deck = VocabDeck(
        "test", "", "es",
        listOf(
            entry(1, "ser", Pos.Verb, "es", "fue", "soy"),
            entry(2, "ir", Pos.Verb, "fue", "voy"),
            entry(3, "como", Pos.Conjunction),
            entry(4, "comer", Pos.Verb, "como", "comemos"),
            entry(5, "casa", Pos.Noun, "casa"),
            entry(6, "hablar", Pos.Verb),
            entry(7, "teléfono", Pos.Noun),
            entry(8, "bueno", Pos.Adjective),
        ),
        bundled = true,
    )

    private fun utterance(id: String, text: String, at: Long = 0, corrected: List<Span> = emptyList(), language: String = "es") =
        Utterance("check:$id", "check", language, text, corrected, at)

    private fun analyse(vararg utterances: Utterance, lemmatizer: Lemmatizer = Lemmatizer.Identity, on: VocabDeck = deck) =
        VocabAnalyser(lemmatizer, utc).analyse(on, utterances.toList())

    @Test
    fun deckFormsCountTowardTheirExactLexemesAndAmbiguousFormsCountTowardEach() {
        val report = analyse(utterance("1", "Fue genial"))
        assertEquals(setOf("ser/v", "ir/v"), report.used.keys)
        assertEquals(0, report.bySpelling)
        assertFalse(report.used.getValue("ser/v").bySpelling)
    }

    @Test
    fun correctedSpansDoNotCount() {
        val text = "Hablamos en casa"
        val report = analyse(utterance("1", text, corrected = listOf(Span(0, 8))))
        assertNull(report.used["hablar/v"])
        assertEquals(1, report.tried)
        assertTrue("casa/n" in report.used)
        val phrases = deck.copy(entries = deck.entries + entry(9, "a menudo", Pos.Adverb))
        val repeated = "Casa [casa] a menudo a menudo"
        val correctedPhrase = repeated.indexOf("a menudo")
        val matched = analyse(utterance("phrases", repeated, corrected = listOf(Span(correctedPhrase, correctedPhrase + 1))), on = phrases)
        assertEquals(setOf("casa/n", "a menudo/adv"), matched.used.keys)
        assertEquals(1, matched.used.getValue("a menudo/adv").count)
    }

    @Test
    fun fallbackFoldingMatchesBySpellingWithIdentity() {
        val report = analyse(utterance("1", "Hablamos de casas buenas y del telefono"))
        assertTrue("hablar/v" in report.used)
        assertTrue("casa/n" in report.used)
        assertTrue("bueno/adj" in report.used)
        assertTrue("teléfono/n" in report.used)
        assertTrue(report.used.getValue("hablar/v").bySpelling)
        assertTrue(report.bySpelling >= 3)
    }

    @Test
    fun knownLexemesCountOnlyTowardTheirPartOfSpeech() {
        val morphology = Lemmatizer { word, _ ->
            when (word) {
                "como" -> setOf(Lexeme("como", Pos.Conjunction))
                else -> setOf(Lexeme(word, Pos.Unknown))
            }
        }
        // "como" is in comer's forms column too: the deck's own forms still count.
        val byLexeme = analyse(utterance("1", "Es como dices"), lemmatizer = morphology)
        assertTrue("como/conj" in byLexeme.used)
        assertTrue("ser/v" in byLexeme.used)

        val only = VocabDeck("t2", "", "es", listOf(entry(1, "como", Pos.Conjunction), entry(2, "como", Pos.Adverb)), true)
        val report = analyse(utterance("1", "como"), lemmatizer = morphology, on = only)
        assertEquals(setOf("como/conj"), report.used.keys)
        val byLemma = analyse(utterance("1", "como"), lemmatizer = morphology, on = only.byLemma())
        assertEquals(setOf("como"), byLemma.used.keys)
        assertEquals(1, only.byLemma().size)
    }

    @Test
    fun spellingFallbackCountsEveryLexemeWithThatLemma() {
        val only = VocabDeck("t2", "", "es", listOf(entry(1, "como", Pos.Conjunction), entry(2, "como", Pos.Adverb)), true)
        val report = analyse(utterance("1", "como"), on = only)
        assertEquals(setOf("como/conj", "como/adv"), report.used.keys)
        assertTrue(report.used.values.all { it.bySpelling })
    }

    @Test
    fun milestonesAndHeadline() {
        assertEquals(listOf(100, 500, 1000, 2000, 5000), Milestone.tops(5000))
        assertEquals(listOf(100, 500, 1000, 1500), Milestone.tops(1500))
        assertEquals(listOf(8), Milestone.tops(8))
        val report = analyse(utterance("1", "Soy de casa"))
        assertEquals(listOf(Milestone(8, 2)), report.milestones)
        assertEquals(Milestone(8, 2), report.headline)
    }

    @Test
    fun monthlyTrendIsCumulativeForTopWordsAndPerMonthForDistinct() {
        val jan = 1_704_067_200_000L // 2024-01-01
        val mar = 1_709_251_200_000L // 2024-03-01
        val report = analyse(
            utterance("1", "Soy de casa", at = jan),
            utterance("2", "Voy a Zaragoza con Ximena", at = mar),
        )
        assertEquals(listOf("2024-01", "2024-02", "2024-03"), report.trend.map { it.month })
        assertEquals(listOf(2, 2, 3), report.trend.map { it.topUsed })
        assertEquals(0, report.trend[1].distinct)
        // "con" is not in this deck, so it is rarer; one-letter words and names mid-sentence are skipped.
        val march = report.trend[2]
        assertEquals(2, march.distinct) // ir, con
        assertEquals(1, march.rarer)
        assertEquals(50, march.rarerPercent)
    }

    @Test
    fun otherLanguagesAreIgnoredAndFingerprintFollowsTheCorpus() {
        val one = utterance("1", "Soy de casa")
        val two = utterance("2", "Voy")
        val catalan = utterance("3", "Vaig a casa", language = "ca")
        val before = VocabAnalyser.fingerprint(deck, listOf(one, two, catalan), Lemmatizer.Identity)
        assertEquals(before, VocabAnalyser.fingerprint(deck, listOf(two, one), Lemmatizer.Identity))
        assertNotEquals(before, VocabAnalyser.fingerprint(deck, listOf(one), Lemmatizer.Identity))
        assertNotEquals(before, VocabAnalyser.fingerprint(deck.byLemma(), listOf(one, two), Lemmatizer.Identity))
        assertEquals(2, analyse(one, two, catalan).utterances)
    }
}
