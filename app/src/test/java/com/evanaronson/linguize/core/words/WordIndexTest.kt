package com.evanaronson.linguize.core.words

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos
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
        val repeated = build(utterance("many", List(40) { "casa" }.joinToString(" "), now)).language("ca")!!
        for (level in WordLevel.entries) {
            val row = repeated.rows(level).single()
            assertEquals(40, row.right)
            assertEquals(30, row.recent.size)
            assertEquals((0 until 30).map { it * 5 }, row.recent.map { it.start })
        }
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
            }.mapTo(LinkedHashSet()) { Lexeme(it, Pos.Verb) }
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
        assertTrue(ca.morphology)
        assertEquals(ca.distinctWords - 2, ca.distinctLemmas)

        val es = build(utterance("c", "fuimos", 1, language = "es"), lemmatizer = lemmatizer).language("es")!!
        assertEquals(setOf("ir", "ser"), es.lemmas.map { it.lemma }.toSet())
    }

    @Test
    fun identityLemmatizerGroupsNothing() {
        val ca = build(utterance("a", "vaig a casa", 1)).language("ca")!!
        assertFalse(ca.morphology)
        assertEquals(ca.distinctWords, ca.distinctLexemes)
        assertEquals(ca.distinctWords, ca.distinctLemmas)
        assertEquals(0, ca.ambiguousForms)
        assertEquals(Lexeme("casa", Pos.Unknown), ca.word("casa")!!.lexemes.single())
    }

    /** A small fake morphology: homographs and a verb form with two lemmas. */
    private val lexicon = Lemmatizer { word, _ ->
        when (word) {
            "la" -> setOf(Lexeme("el", Pos.Article), Lexeme("ella", Pos.Pronoun))
            "el" -> setOf(Lexeme("el", Pos.Article))
            "els" -> setOf(Lexeme("el", Pos.Article), Lexeme("ell", Pos.Pronoun))
            "veig", "veus", "veure" -> setOf(Lexeme("veure", Pos.Verb))
            "fuimos", "fue" -> setOf(Lexeme("ir", Pos.Verb), Lexeme("ser", Pos.Verb))
            "vaig" -> setOf(Lexeme("anar", Pos.Verb))
            "casa" -> setOf(Lexeme("casa", Pos.Noun))
            "bonic" -> setOf(Lexeme("bonic", Pos.Adjective))
            else -> setOf(Lexeme(word, Pos.Unknown))
        }
    }

    @Test
    fun homographsStayApartAsLexemes() {
        val ca = build(
            utterance("a", "veig la casa", 1),
            utterance("b", "la veig", 2),
            utterance("c", "el veus bonic", 3),
            lemmatizer = lexicon,
        ).language("ca")!!
        val article = ca.lexeme("el/art")!!
        val pronoun = ca.lexeme("ella/pron")!!
        // "la" is ambiguous: it counts toward both lexemes and is marked so in each.
        assertEquals(listOf("la", "el"), article.forms)
        assertEquals(3, article.right)
        assertEquals(setOf("la"), article.ambiguousForms)
        assertEquals(listOf("la"), pronoun.forms)
        assertEquals(2, pronoun.right)
        assertEquals(setOf("la"), pronoun.ambiguousForms)
        assertTrue(ca.word("la")!!.ambiguous)
        assertFalse(ca.word("el")!!.ambiguous)
        assertEquals(1, ca.ambiguousForms)

        val veure = ca.lexeme("veure/v")!!
        assertEquals(setOf("veig", "veus"), veure.forms.toSet())
        assertEquals(3, veure.right)
        assertTrue(veure.ambiguousForms.isEmpty())
        assertEquals("veure", veure.label)
        assertTrue(ca.morphology)
    }

    @Test
    fun lemmasGroupLexemesWithTheSameDictionaryForm() {
        val ca = build(utterance("a", "els veig i la veig", 1), utterance("b", "el veus", 2), lemmatizer = lexicon).language("ca")!!
        // "els" is el (article) or ell (pronoun); "la" el (article) or ella (pronoun).
        val el = ca.lemma("el")!!
        assertEquals(listOf(Lexeme("el", Pos.Article)), el.lexemes)
        assertEquals(setOf("els", "la", "el"), el.forms.toSet())
        assertEquals(3, el.right)
        assertEquals(listOf("els"), ca.lemma("ell")!!.forms)
        // Six distinct forms: els, veig, i, la, el, veus. Lexemes: el/art, ell/pron, ella/pron, veure/v, i.
        assertEquals(6, ca.distinctWords)
        assertEquals(5, ca.distinctLexemes)
        assertEquals(5, ca.distinctLemmas)
    }

    @Test
    fun aVerbFormWithSeveralLemmasCountsTowardEach() {
        val es = build(
            utterance("a", "fuimos a casa", 1, language = "es"),
            utterance("b", "fue bonic", 2, language = "es"),
            lemmatizer = lexicon,
        ).language("es")!!
        val ir = es.lexeme("ir/v")!!
        val ser = es.lexeme("ser/v")!!
        assertEquals(listOf("fue", "fuimos"), ir.forms)
        assertEquals(2, ir.right)
        assertEquals(2, ser.right)
        assertEquals(setOf("fuimos", "fue"), ir.ambiguousForms)
        assertEquals(listOf(Lexeme("ser", Pos.Verb)), es.lemma("ser")!!.lexemes)
        // Words written still counts each word once.
        assertEquals(5, es.totalWords)
    }

    @Test
    fun rowsAndEntriesByLevel() {
        val ca = build(utterance("a", "la veig", 1), lemmatizer = lexicon).language("ca")!!
        assertEquals(ca.words, ca.rows(WordLevel.Forms))
        assertEquals(ca.lexemes, ca.rows(WordLevel.Lexemes))
        assertEquals(ca.lemmas, ca.rows(WordLevel.Lemmas))
        assertEquals("ella/pron", ca.entry(WordLevel.Lexemes, "ella/pron")!!.key)
        assertNull(ca.entry(WordLevel.Lexemes, "ella/art"))
        assertEquals(WordLevel.Lexemes, WordLevel.read("x"))
        assertNull(WordLevel.read("z"))
    }

    @Test
    fun csvHasOneRowPerLexemeWithItsPartOfSpeech() {
        val ca = build(utterance("a", "la casa", day("2026-11-01")), utterance("b", "hola", day("2026-11-02")), lemmatizer = lexicon)
            .language("ca")!!
        val lines = WordViews.csv(ca, zone = utc).trimEnd().split("\r\n")
        assertEquals("word,lemma,lexeme_pos,deck_rank,right,wrong,accuracy,first,last", lines[0])
        assertTrue("la,el,art,,1,0,100,2026-11-01,2026-11-01" in lines)
        assertTrue("la,ella,pron,,1,0,100,2026-11-01,2026-11-01" in lines)
        assertTrue("casa,casa,n,,1,0,100,2026-11-01,2026-11-01" in lines)
        assertTrue("hola,hola,,,1,0,100,2026-11-02,2026-11-02" in lines)
        assertEquals(5, lines.size)
    }

    @Test
    fun lexemeSearchAndSortUseTheLemma() {
        val ca = build(utterance("a", "la la la veig", 1), lemmatizer = lexicon).language("ca")!!
        assertEquals(listOf("w:ella/pron"), WordList.arrange(WordList.rows(ca, null, WordLevel.Lexemes), WordFilter.All, WordSort.MostUsed, query = "ella").map { it.key })
        assertEquals(listOf("el", "ella", "veure"), WordList.arrange(WordList.rows(ca, null, WordLevel.Lexemes), WordFilter.All, WordSort.Alphabetical).map { it.label })
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
        val rows = WordList.rows(index.language("ca")!!, null, WordLevel.Forms)
        fun keys(filter: WordFilter, sort: WordSort, query: String = "") = WordList.arrange(rows, filter, sort, query, Locale.forLanguageTag("ca")).map { it.label }
        assertEquals(words.size, rows.size)
        assertEquals("bé", keys(WordFilter.All, WordSort.MostUsed).first())
        assertEquals("zebra", keys(WordFilter.All, WordSort.Newest).first())
        assertEquals(listOf("àvia", "bé", "ça", "cotxe"), keys(WordFilter.All, WordSort.Alphabetical).take(4))
        assertEquals(listOf("perque"), keys(WordFilter.Tried, WordSort.MostUsed))
        assertEquals(keys(WordFilter.All, WordSort.MostUsed) - "perque", keys(WordFilter.Used, WordSort.MostUsed))
        // Without a deck there is nothing "not yet".
        assertTrue(keys(WordFilter.NotYet, WordSort.MostUsed).isEmpty())
        assertEquals(listOf("àvia"), keys(WordFilter.All, WordSort.MostUsed, "avia"))
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
        val csv = WordViews.csv(index.language("ca")!!, zone = utc)
        val lines = csv.trimEnd().split("\r\n")
        assertEquals("word,lemma,lexeme_pos,deck_rank,right,wrong,accuracy,first,last", lines[0])
        assertEquals("bon,bon,,,2,0,100,2026-10-01,2026-11-02", lines[1])
        assertTrue("vespre,vespre,,,0,1,0,2026-11-02,2026-11-02" in lines)
        assertEquals(4, lines.size)
    }
}
