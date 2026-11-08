package com.evanaronson.linguize.core.words

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.corpus.Span
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.vocab.DeckEntry
import com.evanaronson.linguize.core.vocab.VocabDeck
import com.evanaronson.linguize.core.vocab.VocabSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class WordListTest {
    private val utc = ZoneOffset.UTC
    private fun day(date: String) = LocalDate.parse(date).atStartOfDay(utc).toInstant().toEpochMilli()

    private val lexicon = Lemmatizer { word, _ ->
        when (word) {
            "la" -> setOf(Lexeme("el", Pos.Article), Lexeme("ella", Pos.Pronoun))
            "el" -> setOf(Lexeme("el", Pos.Article))
            "fue" -> setOf(Lexeme("ir", Pos.Verb), Lexeme("ser", Pos.Verb))
            "es" -> setOf(Lexeme("ser", Pos.Verb))
            "casa", "casas" -> setOf(Lexeme("casa", Pos.Noun))
            "veo" -> setOf(Lexeme("ver", Pos.Verb))
            "ornitorrinco" -> setOf(Lexeme("ornitorrinco", Pos.Noun))
            else -> setOf(Lexeme(word, Pos.Unknown))
        }
    }

    /** A small ranked deck of lexemes, with one phrase. */
    private val deck = VocabDeck(
        id = "test", name = "Test", language = "es", bundled = true,
        entries = listOf(
            DeckEntry(1, "el", "art"),
            DeckEntry(2, "ser", "v"),
            DeckEntry(3, "ir", "v"),
            DeckEntry(4, "casa", "n", gloss = "house"),
            DeckEntry(5, "ella", "pron"),
            DeckEntry(6, "tener", "v"),
            DeckEntry(7, "a menudo", "adv"),
            DeckEntry(8, "ver", "v"),
        ),
    )

    private fun utterance(id: String, text: String, at: Long, corrected: List<Span> = emptyList()) =
        Utterance("check:$id", "check", "es", text, corrected, at)

    private val corpus = listOf(
        utterance("a", "la casa es bonita", day("2026-10-01")),
        utterance("b", "fue a menudo a casa", day("2026-10-20")),
        utterance("c", "veo un ornitorrinco", day("2026-11-03"), corrected = listOf(Span(0, 3))),
    )

    private fun words() = WordIndex.build(corpus, lexicon, now = day("2026-11-08"), zone = utc).language("es")!!
    private fun coverage() = DeckCoverage.build(words(), deck, lexicon)

    @Test
    fun coverageCountsDeckRowsFromTheWritersForms() {
        val coverage = coverage()
        // "la" reaches el/art and ella/pron, "fue" reaches ir/v and ser/v: an ambiguous form counts toward each.
        assertEquals(setOf("el/art", "ella/pron", "casa/n", "ser/v", "ir/v", "a menudo/adv"), coverage.used)
        assertEquals(2, coverage.uses.getValue("casa/n").right)
        assertEquals(2, coverage.uses.getValue("ser/v").right) // es + fue
        assertEquals(listOf("es", "fue"), coverage.uses.getValue("ser/v").forms.sorted())
        // "veo" was replaced by a fix: tried, not used.
        assertFalse("ver/v" in coverage.used)
        assertEquals(1, coverage.uses.getValue("ver/v").tried)
        assertEquals(listOf(8), coverage.milestones.map { it.top })
        assertEquals(6, coverage.milestones.single().used)
        assertEquals(1, coverage.rankOfForm("la"))
        assertNull(coverage.rankOfForm("bonita"))
        assertEquals(4, coverage.rankOf(Lexeme("casa", Pos.Noun)))
    }

    @Test
    fun theLexemeListIsTheDeckPlusTheWritersOtherWords() {
        val rows = WordList.rows(words(), coverage(), WordLevel.Lexemes)
        val deckRows = rows.filter { it.deckEntry != null }
        assertEquals(8, deckRows.size)
        val own = rows.filter { it.deckEntry == null }.map { it.label }.toSet()
        // Words of the writer's the deck doesn't have: not in list.
        assertEquals(setOf("bonita", "a", "un", "ornitorrinco", "menudo"), own)
        assertTrue(rows.filter { it.deckEntry == null }.all { it.notInList && it.rank == null })
        val casa = rows.single { it.key == "d:casa/n" }
        assertEquals(4, casa.rank)
        assertEquals(Pos.Noun, casa.pos)
        assertEquals("house", casa.gloss)
        assertTrue(casa.used)

        val counts = WordList.counts(rows)
        assertEquals(rows.size, counts[WordFilter.All])
        assertEquals(2, counts[WordFilter.NotYet]) // tener, ver
        assertEquals(1, counts[WordFilter.Tried]) // ver
        assertEquals(listOf("tener", "ver"), WordList.arrange(rows, WordFilter.NotYet, WordSort.Rank).map { it.label })
        assertEquals("el", WordList.arrange(rows, WordFilter.All, WordSort.Rank).first().label)
        // Words outside the deck sort after every ranked row.
        assertTrue(WordList.arrange(rows, WordFilter.All, WordSort.Rank).takeLast(5).all { it.notInList })
    }

    @Test
    fun theFormListRanksEachFormByTheBestRowItReached() {
        val rows = WordList.rows(words(), coverage(), WordLevel.Forms)
        val la = rows.single { it.key == "w:la" }
        assertEquals(1, la.rank)
        assertFalse(la.notInList)
        assertTrue(rows.single { it.key == "w:bonita" }.notInList)
        // Deck rows no form reached at all appear as not yet; reached ones are the forms themselves.
        assertEquals(listOf("d:tener/v"), rows.filter { it.deckEntry != null }.map { it.key })
        assertTrue(rows.none { it.key == "d:casa/n" })
    }

    @Test
    fun theLemmaListUsesTheDeckByLemma() {
        val byLemma = deck.byLemma()
        val words = words()
        val rows = WordList.rows(words, DeckCoverage.build(words, byLemma, lexicon), WordLevel.Lemmas)
        assertTrue(rows.any { it.key == "d:ser" && it.used })
        assertTrue(rows.filter { it.deckEntry != null }.all { it.pos == null })
    }

    @Test
    fun withoutADeckTheListIsJustTheWritersWords() {
        val words = words()
        val rows = WordList.rows(words, null, WordLevel.Lexemes)
        assertEquals(words.lexemes.size, rows.size)
        assertTrue(rows.none { it.notInList || it.rank != null })
        assertTrue(WordList.arrange(rows, WordFilter.NotYet, WordSort.MostUsed).isEmpty())
    }

    @Test
    fun oneChartFollowsAllWordsAndTheTopGroup() {
        val words = words()
        val points = WordList.growth(words.rows(WordLevel.Lexemes), coverage(), utc)
        assertEquals(listOf("2026-10-01", "2026-10-20", "2026-11-03").map(LocalDate::parse), points.map { it.day })
        assertEquals(4, points.first().top)
        val last = points.last()
        assertEquals(words.distinctLexemes, last.words)
        assertEquals(6, last.top)
        assertTrue(points.all { it.top <= it.words + 1 })
    }

    @Test
    fun wordsToTryNextSkipWhatWasUsed() {
        val coverage = coverage()
        val next = VocabSuggestions.next(coverage.deck, coverage.used).flatMap { it.entries }.map { it.lemma }
        assertEquals(listOf("tener", "ver"), next)
        assertEquals(listOf("tener", "ver"), VocabSuggestions.targets(coverage.deck, coverage.used).map { it.lemma })
    }

    @Test
    fun csvCarriesTheDeckRank() {
        val lines = WordViews.csv(words(), coverage(), utc).trimEnd().split("\r\n")
        assertEquals("word,lemma,lexeme_pos,deck_rank,count,tried,first,last", lines[0])
        assertTrue(lines.toString(), "casa,casa,n,4,2,0,2026-10-01,2026-10-20" in lines)
        assertTrue(lines.toString(), "la,ella,pron,5,1,0,2026-10-01,2026-10-01" in lines)
        assertTrue(lines.toString(), "bonita,bonita,,,1,0,2026-10-01,2026-10-01" in lines)
    }
}
