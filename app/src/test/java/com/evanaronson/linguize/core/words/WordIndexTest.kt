package com.evanaronson.linguize.core.words

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Span
import com.evanaronson.linguize.core.corpus.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class WordIndexTest {
    private val utc = ZoneOffset.UTC
    private fun day(date: String) = LocalDate.parse(date).atStartOfDay(utc).toInstant().toEpochMilli()
    private val now = day("2026-11-08") + 3_600_000

    private fun utterance(id: String, text: String, at: Long, language: String? = "ca", corrected: List<Span> = emptyList()) =
        Utterance("check:$id", "check", language, text, corrected, at)

    private fun span(text: String, word: String): Span = text.indexOf(word).let { Span(it, it + word.length) }

    private fun build(vararg utterances: Utterance, lemmatizer: Lemmatizer = Lemmatizer.Identity) =
        WordIndex.build(utterances.toList(), lemmatizer, now, utc)

    @Test
    fun countsEachFormWithMessagesAndDates() {
        val index = build(
            utterance("a", "Vull anar a casa", day("2026-10-01")),
            utterance("b", "A casa, a casa", day("2026-11-02")),
        )
        val ca = index.language("ca")!!
        val casa = ca.word("casa")!!
        assertEquals(3, casa.right)
        assertEquals(0, casa.tried)
        assertEquals(2, casa.messages)
        assertEquals(day("2026-10-01"), casa.firstAt)
        assertEquals(day("2026-11-02"), casa.lastAt)
        assertEquals(3, ca.word("a")!!.right)
        assertEquals(4, ca.distinctWords)
        assertEquals(8, ca.totalWords)
        assertEquals(4, ca.wordsThisMonth)
        assertEquals(0, ca.newThisMonth)
        assertEquals(2, ca.messages)
    }

    @Test
    fun wordsInCorrectedSpansCountAsTriedNotKnown() {
        val text = "Estic content perque ve"
        val index = build(utterance("a", text, day("2026-11-01"), corrected = listOf(span(text, "perque"))))
        val ca = index.language("ca")!!
        val perque = ca.word("perque")!!
        assertEquals(0, perque.right)
        assertEquals(1, perque.tried)
        assertFalse(perque.known)
        assertNull(perque.firstRightAt)
        assertEquals(3, ca.distinctWords)
        assertEquals(4, ca.totalWords)
        assertTrue(perque.recent.single().tried)
    }

    @Test
    fun aCorrectedSpanThatCutsIntoAWordMarksTheWholeWord() {
        val text = "Com estas?"
        // The fix replaced only the "a" of "estas" with "à".
        val at = text.indexOf("estas") + 3
        val index = build(utterance("a", text, day("2026-11-01"), corrected = listOf(Span(at, at + 1))))
        assertEquals(1, index.language("ca")!!.word("estas")!!.tried)
        assertEquals(1, index.language("ca")!!.word("com")!!.right)
    }

    @Test
    fun aWordTriedThenUsedRightBecomesKnownFromThatDay() {
        val wrong = "Ho faig perque vull"
        val index = build(
            utterance("a", wrong, day("2026-09-01"), corrected = listOf(span(wrong, "perque"))),
            utterance("b", "Ho faig perquè vull", day("2026-10-05")),
            utterance("c", "No ho sé perque sí", day("2026-11-03")),
        )
        val ca = index.language("ca")!!
        val perque = ca.word("perque")!!
        assertEquals(1, perque.tried)
        assertEquals(1, perque.right)
        assertEquals(day("2026-11-03"), perque.firstRightAt)
        assertEquals(day("2026-10-05"), ca.word("perquè")!!.firstRightAt)
    }

    @Test
    fun languagesAreKeptApartMostUsedFirst() {
        val index = build(
            utterance("a", "hola que tal", 1, language = "es"),
            utterance("b", "bon dia a tothom avui", 2, language = "ca"),
            utterance("c", "ok", 3, language = null),
        )
        assertEquals(listOf("ca", "es", null), index.languages.map { it.language })
        assertNull(index.language("es")!!.word("bon"))
    }

    @Test
    fun namesAreLeftOutAndListedApart() {
        val index = build(
            utterance("a", "Avui he vist la Marta", 1),
            utterance("b", "Marta diu que sí", 2),
            utterance("c", "Rosa, la Rosa i una rosa", 3),
        )
        val ca = index.language("ca")!!
        assertNull(ca.word("marta"))
        assertEquals(listOf("Marta" to 2, "Rosa" to 1), ca.names.map { it.written to it.count })
        // Capitalized at the start of a sentence, but also written lowercase: a word there.
        assertEquals(2, ca.word("rosa")!!.right)
    }

    @Test
    fun growthCountsKnownFormsByTheDayFirstUsedRight() {
        val index = build(
            utterance("a", "bon dia", day("2026-10-01")),
            utterance("b", "bon vespre", day("2026-10-03")),
            utterance("c", "bon dia", day("2026-11-01")),
        )
        assertEquals(
            listOf(GrowthPoint(LocalDate.parse("2026-10-01"), 2), GrowthPoint(LocalDate.parse("2026-10-03"), 3)),
            index.language("ca")!!.growth,
        )
    }

    @Test
    fun lemmaGroupingUsesTheLemmatizer() {
        val lemmatizer = Lemmatizer { word, _ ->
            when (word) {
                "vaig", "anem", "anar" -> setOf("anar")
                "fuimos" -> setOf("ir", "ser")
                else -> setOf(word)
            }
        }
        val index = build(
            utterance("a", "vaig a casa i anem junts", 1),
            utterance("b", "volem anar", 2),
            lemmatizer = lemmatizer,
        )
        val ca = index.language("ca")!!
        val anar = ca.lemma("anar")!!
        assertEquals(3, anar.right)
        assertEquals(setOf("vaig", "anem", "anar"), anar.forms.toSet())
        assertEquals(2, anar.messages)
        assertTrue(ca.lemmasDiffer)
        assertEquals(ca.distinctWords - 2, ca.distinctLemmas)

        val es = build(utterance("c", "fuimos", 1, language = "es"), lemmatizer = lemmatizer).language("es")!!
        assertEquals(setOf("ir", "ser"), es.lemmas.map { it.lemma }.toSet())
    }

    @Test
    fun identityLemmatizerGroupsNothing() {
        val ca = build(utterance("a", "vaig a casa", 1)).language("ca")!!
        assertFalse(ca.lemmasDiffer)
        assertEquals(ca.distinctWords, ca.distinctLemmas)
    }

    @Test
    fun cacheRebuildsOnlyWhenTheCorpusChanges() {
        val cache = WordIndexCache(Lemmatizer.Identity, clock = { now }, zone = { utc })
        val a = utterance("a", "bon dia", 1)
        val b = utterance("b", "bona nit", 2)
        val first = cache.index(listOf(a, b))
        assertSame(first, cache.index(listOf(b, a)))
        assertEquals(1, cache.builds)

        // Deleting a check removes its words from the next read.
        val afterDelete = cache.index(listOf(a))
        assertEquals(2, cache.builds)
        assertNull(afterDelete.language("ca")!!.word("nit"))
        assertNotEquals(first.fingerprint, afterDelete.fingerprint)
    }

    @Test
    fun fingerprintChangesWithTextLanguageAndCorrections() {
        val a = utterance("a", "bon dia", 1)
        val base = CorpusFingerprint.of(listOf(a))
        assertEquals(base, CorpusFingerprint.of(listOf(a.copy())))
        assertNotEquals(base, CorpusFingerprint.of(listOf(a.copy(text = "bon dia!"))))
        assertNotEquals(base, CorpusFingerprint.of(listOf(a.copy(language = "es"))))
        assertNotEquals(base, CorpusFingerprint.of(listOf(a.copy(corrected = listOf(Span(0, 3))))))
        assertNotEquals(base, CorpusFingerprint.of(emptyList()))
    }

    @Test
    fun arrangeSortsFiltersAndSearches() {
        val text = "ça va. Bé, molt bé. Àvia, cotxe, cotxe"
        val index = build(
            utterance("a", text, day("2026-10-01")),
            utterance("b", "perque", day("2026-11-01"), corrected = listOf(Span(0, 6))),
            utterance("c", "zebra", day("2026-11-05")),
        )
        val words = index.language("ca")!!.words
        assertEquals("bé", WordViews.arrange(words, WordSort.MostUsed).first().key)
        assertEquals("zebra", WordViews.arrange(words, WordSort.Newest).first().key)
        assertEquals(listOf("àvia", "bé", "ça", "cotxe"), WordViews.arrange(words, WordSort.Alphabetical, locale = Locale.forLanguageTag("ca")).take(4).map { it.key })
        assertEquals(listOf("perque"), WordViews.arrange(words, WordSort.Tried).map { it.key })
        assertEquals(listOf("àvia"), WordViews.arrange(words, WordSort.MostUsed, query = "avia").map { it.key })
    }

    @Test
    fun contextIsTheWritersSentenceWithTheWordMarked() {
        val text = "Bon dia. Avui vaig a la platja amb els amics. Adéu"
        val index = build(utterance("a", text, 1))
        val ca = index.language("ca")!!
        val use = ca.word("platja")!!.recent.single()
        val context = WordViews.context(use, ca.texts)
        assertNotNull(context)
        assertEquals("Avui vaig a la platja amb els amics.", context!!.sentence)
        assertEquals("platja", context.sentence.substring(context.start, context.end))
    }

    @Test
    fun longSentencesAreCutAroundTheWord() {
        val text = (1..60).joinToString(" ") { "paraula$it".filter { c -> c.isLetter() } + "x" } + " objectiu " +
            (1..60).joinToString(" ") { "final" }
        val index = build(utterance("a", text, 1))
        val ca = index.language("ca")!!
        val context = WordViews.context(ca.word("objectiu")!!.recent.single(), ca.texts)!!
        assertTrue(context.sentence.length <= WordViews.MAX_SENTENCE)
        assertTrue(context.cutBefore && context.cutAfter)
        assertEquals("objectiu", context.sentence.substring(context.start, context.end))
    }

    @Test
    fun csvHasOneRowPerFormAndEscapes() {
        val index = build(
            utterance("a", "bon dia", day("2026-10-01")),
            utterance("b", "bon vespre", day("2026-11-02"), corrected = listOf(Span(4, 10))),
        )
        val csv = WordViews.csv(index.language("ca")!!, utc)
        val lines = csv.trimEnd().split("\r\n")
        assertEquals("word,lemma,count,tried,first,last", lines[0])
        assertEquals("bon,bon,2,0,2026-10-01,2026-11-02", lines[1])
        assertTrue("vespre,vespre,0,1,2026-11-02,2026-11-02" in lines)
        assertEquals(4, lines.size)
    }
}
