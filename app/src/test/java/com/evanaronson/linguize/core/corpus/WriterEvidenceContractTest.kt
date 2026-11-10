package com.evanaronson.linguize.core.corpus

import com.evanaronson.linguize.core.conjugations.Cell
import com.evanaronson.linguize.core.conjugations.ConjugationMap
import com.evanaronson.linguize.core.morphology.Morphology
import com.evanaronson.linguize.core.morphology.MorphologyLemmatizer
import com.evanaronson.linguize.core.morphology.Person
import com.evanaronson.linguize.core.morphology.Tense
import com.evanaronson.linguize.core.vocab.DeckEntry
import com.evanaronson.linguize.core.vocab.VocabAnalyser
import com.evanaronson.linguize.core.vocab.VocabDeck
import com.evanaronson.linguize.core.words.DeckCoverage
import com.evanaronson.linguize.core.words.WordIndex
import com.evanaronson.linguize.testing.KnownBonanzaDefect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.ZoneOffset

/** Shared examples keep a future evidence-pipeline cleanup honest across its real consumers. */
class WriterEvidenceContractTest {
    private val morphology = Morphology()
    private val lemmatizer = MorphologyLemmatizer(morphology)
    private val utc = ZoneOffset.UTC
    private val now = 1_770_000_000_000L
    private val deck = VocabDeck(
        "evidence", "Evidence", "es",
        listOf(DeckEntry(1, "hablar", "v"), DeckEntry(2, "casa", "n"), DeckEntry(3, "a menudo", "adv")),
        bundled = false,
    )

    private fun say(id: String, text: String, language: String = "es", corrected: List<Span> = emptyList(), at: Long = now) =
        Utterance("check:$id", "check", language, text, corrected, at)

    private fun fixture(): List<Utterance> {
        val first = "Ayer hablé a menudo en casa."
        val casa = first.indexOf("casa")
        return listOf(
            say("earlier", first, corrected = listOf(Span(casa, casa + 4)), at = now - 86_400_000),
            say("later", "Hoy hablo en casa."),
            say("catalan", "Ahir vaig parlar.", language = "ca"),
        )
    }

    @Test
    fun acceptedSpansLanguageAndOriginalExamplesAgreeAcrossConsumers() {
        val evidence = fixture()
        val index = WordIndex.build(evidence, lemmatizer, now, utc)
        val words = index.language("es")!!
        val coverage = DeckCoverage.build(words, deck, lemmatizer)
        val vocabulary = VocabAnalyser(lemmatizer, utc).analyse(deck, evidence)
        val conjugations = ConjugationMap.build(evidence, "es", morphology, utc)

        assertEquals(setOf("hablar/v", "casa/n", "a menudo/adv"), coverage.used)
        assertEquals(coverage.used, vocabulary.used.keys)
        assertEquals(2, coverage.uses.getValue("hablar/v").right)
        assertEquals(2, vocabulary.used.getValue("hablar/v").count)
        assertEquals(1, words.word("casa")!!.right)
        assertEquals(1, words.word("casa")!!.tried)
        assertEquals(1, coverage.uses.getValue("casa/n").right)
        assertEquals(1, coverage.uses.getValue("casa/n").tried)
        assertEquals(1, vocabulary.used.getValue("casa/n").count)

        // The newer index keeps both attempts; the older Chat-target analyser keeps only uses
        // outside accepted spans. Both must point into the original writer text.
        val contexts = coverage.uses.getValue("casa/n").recent
        assertEquals(listOf("check:later", "check:earlier"), contexts.map { it.utteranceId })
        assertEquals(listOf(false, true), contexts.map { it.tried })
        contexts.forEach { use ->
            val original = evidence.single { it.id == use.utteranceId }.text
            assertEquals("casa", original.substring(use.start, use.end))
        }
        assertEquals("check:later", vocabulary.used.getValue("casa/n").examples.single().utterance)
        assertEquals(2, conjugations.utterances)
        assertEquals(
            mapOf("hablar" to 1.0),
            conjugations.stats(Cell(Tense.Preterite, Person.S1))!!.verbs,
        )
        assertEquals(mapOf("hablar" to 1.0), conjugations.stats(Cell(Tense.Present, Person.S1))!!.verbs)
        assertNull(words.word("vaig"))
        assertTrue(index.language("ca")!!.words.any { it.form == "vaig" })
    }

    @Test
    fun deletingEvidenceRemovesItsUniqueVocabularyAndConjugationUsesOnRebuild() = runBlocking {
        var stored = fixture()
        val source = object : CorpusSource {
            override val name = "check"
            override suspend fun utterances() = stored
        }
        val corpus = Corpus(listOf(source))
        val before = corpus.utterances()
        val initialWords = WordIndex.build(before, lemmatizer, now, utc).language("es")!!
        assertTrue("a menudo/adv" in DeckCoverage.build(initialWords, deck, lemmatizer).used)
        assertTrue(Cell(Tense.Preterite, Person.S1) in ConjugationMap.build(before, "es", morphology, utc).cells)

        stored = stored.filterNot { it.id == "check:earlier" }
        val after = corpus.utterances()
        val words = WordIndex.build(after, lemmatizer, now, utc).language("es")!!
        val coverage = DeckCoverage.build(words, deck, lemmatizer)
        val vocabulary = VocabAnalyser(lemmatizer, utc).analyse(deck, after)
        val conjugations = ConjugationMap.build(after, "es", morphology, utc)

        assertEquals(setOf("hablar/v", "casa/n"), coverage.used)
        assertEquals(coverage.used, vocabulary.used.keys)
        assertEquals(1, coverage.uses.getValue("hablar/v").right)
        assertEquals(0, coverage.uses.getValue("casa/n").tried)
        assertEquals(setOf("check:later"), coverage.uses.values.flatMap { it.recent }.map { it.utteranceId }.toSet())
        assertNull(conjugations.stats(Cell(Tense.Preterite, Person.S1)))
        assertEquals(mapOf("hablar" to 1.0), conjugations.stats(Cell(Tense.Present, Person.S1))!!.verbs)
    }

    @Test
    @Category(KnownBonanzaDefect::class)
    fun aBracketedGapIsNotVocabularyKnowledgeForChatTargets() {
        val evidence = listOf(say("gap", "quiero [casa]"))
        val words = WordIndex.build(evidence, lemmatizer, now, utc).language("es")!!
        assertNull(words.word("casa"))
        assertFalse("casa/n" in DeckCoverage.build(words, deck, lemmatizer).used)

        assertFalse(
            "A word explicitly left in a gap must not be counted as independently used by Chat's vocabulary analyser",
            "casa/n" in VocabAnalyser(lemmatizer, utc).analyse(deck, evidence).used,
        )
    }

    @Test
    @Category(KnownBonanzaDefect::class)
    fun aPhraseInsideABracketedGapIsNotDeckCoverage() {
        val evidence = listOf(say("phrase-gap", "quiero [a menudo]"))
        val words = WordIndex.build(evidence, lemmatizer, now, utc).language("es")!!
        assertEquals(listOf("quiero"), words.words.map { it.form })

        assertFalse(
            "Phrase matching must respect the same excluded gap as its constituent words",
            "a menudo/adv" in DeckCoverage.build(words, deck, lemmatizer).used,
        )
    }

    @Test
    @Category(KnownBonanzaDefect::class)
    fun spanishPerfectCannotJoinTwoSentences() {
        val report = ConjugationMap.build(listOf(say("boundary", "he. Comido")), "es", morphology, utc)
        assertNull("A sentence boundary separates the auxiliary from the participle", report.stats(Cell(Tense.Perfect, Person.S1)))
    }

    @Test
    @Category(KnownBonanzaDefect::class)
    fun catalanPeriphrasticPastCannotJoinTwoSentences() {
        val report = ConjugationMap.build(listOf(say("boundary", "vaig. Parlar", "ca")), "ca", morphology, utc)
        assertNull("A sentence boundary separates the auxiliary from the infinitive", report.stats(Cell(Tense.PeriphrasticPast, Person.S1)))
    }

    @Test
    @Category(KnownBonanzaDefect::class)
    fun removingAGapDoesNotMakeItsNeighboursACompound() {
        val report = ConjugationMap.build(listOf(say("gap", "vaig [tomorrow] parlar", "ca")), "ca", morphology, utc)
        assertNull("Excluded text remains a boundary for compound recognition", report.stats(Cell(Tense.PeriphrasticPast, Person.S1)))
    }

    @Test
    @Category(KnownBonanzaDefect::class)
    fun corpusPropagatesCancellationInsteadOfReturningSuccessfulEmptyEvidence() = runBlocking {
        val cancelled = CancellationException("superseded evidence request")
        var readNextSource = false
        val source = object : CorpusSource {
            override val name = "check"
            override suspend fun utterances(): List<Utterance> = throw cancelled
        }
        val next = object : CorpusSource {
            override val name = "next"
            override suspend fun utterances(): List<Utterance> {
                readNextSource = true
                return emptyList()
            }
        }

        val result = runCatching { Corpus(listOf(source, next)).utterances() }
        assertSame("Cancellation must reach the caller that owns the request", cancelled, result.exceptionOrNull())
        assertFalse("A cancelled read must not proceed to another source", readNextSource)
    }
}
