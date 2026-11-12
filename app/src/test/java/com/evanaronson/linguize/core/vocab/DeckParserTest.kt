package com.evanaronson.linguize.core.vocab

import com.evanaronson.linguize.core.corpus.Pos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeckParserTest {
    private fun parsed(text: String, language: String? = "es") = DeckParser.parse(text, language) as DeckParse.Parsed

    private fun assertContiguous(entries: List<DeckEntry>) =
        assertEquals((1..entries.size).toList(), entries.map { it.rank })

    @Test
    fun spanishAssetIsFiveThousandUniqueLexemesWithContiguousRanks() {
        val raw = File("src/main/assets/lab_vocab_es.csv").readLines()
        assertEquals("rank,lemma,pos,count,forms", raw.first())
        val rows = raw.drop(1).map { it.split(',') }
        assertEquals(5000, rows.size)
        assertEquals((1..5000).map(Int::toString), rows.map { it[0] })
        assertEquals(5000, rows.map { it[1] to it[2] }.toSet().size)
        val known = setOf("n", "v", "adj", "adv", "other", "pron", "num", "prep", "interj", "determiner", "conj", "art",
            "contraction", "letter")
        assertTrue(rows.map { it[2] }.toSet().minus(known).toString(), rows.all { it[2] in known })

        val deck = parsed(File("src/main/assets/lab_vocab_es.csv").readText())
        assertEquals(5000, deck.entries.size)
        assertEquals(0, deck.skipped)
        assertContiguous(deck.entries)
        assertEquals(5000, deck.entries.map { it.key }.toSet().size)
        val ser = deck.entries.first { it.lemma == "ser" }
        assertEquals(Pos.Verb.token, ser.pos)
        assertTrue("fue" in ser.forms && "soy" in ser.forms)
        assertEquals(Pos.Article.token, deck.entries.first { it.lemma == "el" }.pos)
        assertNull(deck.entries.first { it.lemma == "del" }.pos) // a contraction: no shared part of speech
    }

    @Test
    fun catalanAssetIsFifteenThousandUniqueFormsWithContiguousRanks() {
        // 15,000 forms, so that grouping them into lexemes still leaves the top 5,000 lexemes
        // and lemmas (BundledDecksTest); within DeckParser.MAX_ENTRIES.
        val raw = File("src/main/assets/lab_vocab_ca.csv").readLines()
        assertEquals("rank,form,count", raw.first())
        assertEquals(15_000, raw.size - 1)
        val deck = parsed(File("src/main/assets/lab_vocab_ca.csv").readText(), "ca")
        assertEquals(15_000, deck.entries.size)
        assertTrue(deck.entries.size <= DeckParser.MAX_ENTRIES)
        assertContiguous(deck.entries)
        assertEquals(15_000, deck.entries.map { it.lemma }.toSet().size)
        assertEquals("que", deck.entries.first().lemma)
        assertEquals(100257L, deck.entries.first().count)
        assertTrue(deck.entries.all { it.pos == null })
    }

    @Test
    fun attributionShipsWithTheLists() {
        val sources = File("src/main/assets/lab_vocab_SOURCES.md").readText()
        assertTrue("CC BY-SA 4.0" in sources && "FrequencyWords" in sources && "spanish_data" in sources)
    }

    @Test
    fun headerWithWordAndFrequencyCountSortsByCountDescending() {
        val deck = parsed("Word,Frequency,Meaning\ncasa,10,house\nperro,50,dog\ngato,20,\"cat, feline\"\n")
        assertEquals(listOf("perro", "gato", "casa"), deck.entries.map { it.lemma })
        assertContiguous(deck.entries)
        assertEquals("cat, feline", deck.entries[1].gloss)
        assertEquals(50L, deck.entries[0].count)
    }

    @Test
    fun rankColumnSortsAscendingAndRepeatsKeepTheBestRank() {
        val deck = parsed("term;rank;pos\nhablar;3;verb\ncasa;1;noun\nhablar;7;verb\nhablar;8;noun\n")
        assertEquals(listOf("casa/n", "hablar/v", "hablar/n"), deck.entries.map { it.key })
        assertEquals(1, deck.skipped)
    }

    @Test
    fun ankiPlainTextExportWithDirectivesHtmlAndArticles() {
        val text = """
            #separator:tab
            #html:true
            #guid column:1
            #tags column:4
            a1B2c3	<b>el</b> perro	the dog	animals
            x9Y8z7	la casa<br>	house	home
            q1W2e3	&nbsp;bueno, buena	good
        """.trimIndent()
        val deck = parsed(text)
        assertEquals(listOf("perro", "casa", "bueno"), deck.entries.map { it.lemma })
        assertEquals("the dog", deck.entries[0].gloss)
    }

    @Test
    fun headerlessRankedListAndCatalanElision() {
        val deck = parsed("1,l'home\n2,dona\n3,(la) nit\n", language = "ca")
        assertEquals(listOf("home", "dona", "nit"), deck.entries.map { it.lemma })
    }

    @Test
    fun headerlessTwoColumnFileIsWordThenGloss() {
        val deck = parsed("hola\thello\nadiós\tgoodbye\n")
        assertEquals(listOf("hola", "adiós"), deck.entries.map { it.lemma })
        assertEquals("goodbye", deck.entries[1].gloss)
        assertNull(deck.entries[0].pos)
    }

    @Test
    fun formsColumnIsReadAndMergedForRepeats() {
        val deck = parsed("rank,lemma,pos,count,forms\n1,ir,v,9,va;voy\n2,ir,v,1,fui;Va\n")
        assertEquals(1, deck.entries.size)
        assertEquals(listOf("va", "voy", "fui"), deck.entries.single().forms)
    }

    @Test
    fun failures() {
        assertEquals(DeckParse.Failed(DeckParse.Reason.Empty), DeckParser.parse("\n\n# just a note\n"))
        assertEquals(DeckParse.Failed(DeckParse.Reason.NoWords), DeckParser.parse("rank,count\n1,2\n3,4\n"))
    }
}
