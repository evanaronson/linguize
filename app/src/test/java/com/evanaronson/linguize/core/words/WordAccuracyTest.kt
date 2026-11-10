package com.evanaronson.linguize.core.words

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.corpus.Span
import com.evanaronson.linguize.core.corpus.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Used right, used wrong and the share right: what each count means and how it's ordered. */
class WordAccuracyTest {
    private val utc = ZoneOffset.UTC
    private fun day(n: Int) = LocalDate.parse("2026-10-01").plusDays(n.toLong()).atStartOfDay(utc).toInstant().toEpochMilli()

    private val lexicon = Lemmatizer { word, _ ->
        when (word) {
            "la" -> setOf(Lexeme("el", Pos.Article), Lexeme("ella", Pos.Pronoun))
            "es" -> setOf(Lexeme("ser", Pos.Verb))
            "fue" -> setOf(Lexeme("ir", Pos.Verb), Lexeme("ser", Pos.Verb))
            "casa" -> setOf(Lexeme("casa", Pos.Noun))
            else -> emptySet()
        }
    }

    private fun utterance(id: String, text: String, at: Long, vararg wrong: String) =
        Utterance("check:$id", "check", "es", text, wrong.map { w -> text.indexOf(w).let { Span(it, it + w.length) } }, at)

    /** "es" right twice and fixed once; "la" (ambiguous) the same; "fue" (ambiguous) only fixed. */
    private val corpus = listOf(
        utterance("a", "la casa es bonita", day(1)),
        utterance("b", "la casa es grande", day(2), "es"),
        utterance("c", "fue a casa", day(3), "fue"),
        utterance("d", "es tarde", day(4)),
        utterance("e", "la ornitorrinco", day(5), "la"),
    )

    private fun words() = WordIndex.build(corpus, lexicon, now = day(6), zone = utc).language("es")!!

    @Test
    fun percentIsTheShareRightAndRoundingNeverHidesEitherSide() {
        assertEquals(80, WordAccuracy.percent(12, 3))
        assertEquals(67, WordAccuracy.percent(2, 1))
        assertEquals(33, WordAccuracy.percent(1, 2))
        assertEquals(100, WordAccuracy.percent(3, 0))
        assertEquals(0, WordAccuracy.percent(0, 3))
        // 99.5% rounds to 100, but one use was wrong.
        assertEquals(99, WordAccuracy.percent(199, 1))
        // 0.3% rounds to 0, but one use was right.
        assertEquals(1, WordAccuracy.percent(1, 300))
        // No data, no percent.
        assertNull(WordAccuracy.percent(0, 0))
    }

    @Test
    fun rightAndWrongNeverOverlapSoRightCanBeTheLarger() {
        val words = words()
        val es = words.word("es")!!
        // The count the page called "Tried" is the wrong uses, not the attempts: 2 right, 1 wrong, 3 uses.
        assertEquals(2, es.right)
        assertEquals(1, es.wrong)
        assertEquals(es.tried, es.wrong)
        assertEquals(3, es.uses)
        assertEquals(67, es.accuracy)
        val casa = words.word("casa")!!
        assertEquals(3, casa.right)
        assertEquals(0, casa.wrong)
        assertEquals(100, casa.accuracy)
        // Every use is right or wrong exactly once: forms add up to the words written.
        assertEquals(words.totalWords, words.words.sumOf { it.right + it.wrong })
    }

    @Test
    fun anAmbiguousFormCountsInFullTowardEachLexemeAndOnceTowardEachLemma() {
        val words = words()
        for (key in listOf("el/art", "ella/pron")) {
            val lexeme = words.lexeme(key)!!
            assertEquals(key, 2, lexeme.right)
            assertEquals(key, 1, lexeme.wrong)
        }
        // ser/v gathers "es" (2 right, 1 wrong) and "fue" (1 wrong); ir/v only "fue".
        val ser = words.lexeme("ser/v")!!
        assertEquals(2, ser.right)
        assertEquals(2, ser.wrong)
        assertEquals(50, ser.accuracy)
        val ir = words.lexeme("ir/v")!!
        assertEquals(0, ir.right)
        assertEquals(1, ir.wrong)
        assertEquals(0, ir.accuracy)
        val serLemma = words.lemma("ser")!!
        assertEquals(ser.right to ser.wrong, serLemma.right to serLemma.wrong)
        // The form level is never inflated.
        assertEquals(1, words.word("fue")!!.wrong)
    }

    @Test
    fun mostMisusedPutsTheMostWrongUsesFirstThenTheLowerShareRight() {
        val rows = WordList.rows(words(), null, WordLevel.Lexemes)
        val order = WordList.arrange(rows, WordFilter.All, WordSort.MostMisused).map { it.key.removePrefix("w:") }
        assertEquals(
            listOf(
                // 2 wrong at 50% right comes before 1 wrong at 0%: the bigger habit.
                "ser/v",
                // 1 wrong each: the lower share right first, then (equal) the most recent, then A–Z.
                "ir/v", "el/art", "ella/pron",
                // Never wrong: most recently used first.
                "ornitorrinco/unknown", "tarde/unknown", "a/unknown", "casa/n", "grande/unknown", "bonita/unknown",
            ),
            order,
        )
        assertEquals(WordSort.MostMisused, WordSort.read("most_misused"))
    }

    @Test
    fun usedWrongShowsEveryWordAFixReplacedAtLeastOnce() {
        val rows = WordList.rows(words(), null, WordLevel.Lexemes)
        val wrong = WordList.arrange(rows, WordFilter.Tried, WordSort.MostMisused).map { it.key }
        assertEquals(listOf("w:ser/v", "w:ir/v", "w:el/art", "w:ella/pron"), wrong)
        assertEquals(4, WordList.counts(rows)[WordFilter.Tried])
        // A word both right and wrong is under Used and Used wrong; only ir was never right.
        assertTrue(rows.single { it.key == "w:ser/v" }.let { it.used && it.wrong && !it.tried })
        assertTrue(rows.single { it.key == "w:ir/v" }.let { !it.used && it.wrong && it.tried })
        assertFalse(rows.single { it.key == "w:casa/n" }.wrong)
        assertEquals(WordFilter.Tried, WordFilter.read("tried"))
    }

    @Test
    fun csvHasRightWrongAndAccuracy() {
        val lines = WordViews.csv(words(), zone = utc).trimEnd().split("\r\n")
        assertEquals("word,lemma,lexeme_pos,deck_rank,right,wrong,accuracy,first,last", lines[0])
        assertTrue(lines.toString(), "es,ser,v,,2,1,67,2026-10-02,2026-10-05" in lines)
        assertTrue(lines.toString(), "fue,ir,v,,0,1,0,2026-10-04,2026-10-04" in lines)
        assertTrue(lines.toString(), "fue,ser,v,,0,1,0,2026-10-04,2026-10-04" in lines)
        assertTrue(lines.toString(), "casa,casa,n,,3,0,100,2026-10-02,2026-10-04" in lines)
    }
}
