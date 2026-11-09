package com.evanaronson.linguize.core.corpus

import com.evanaronson.linguize.core.conjugations.ConjugationMap
import com.evanaronson.linguize.core.words.WordIndex
import com.evanaronson.linguize.core.words.WordTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lab: gaps the writer left are not their target-language words, in Words or Conjugations. */
class WriterGapsTest {
    private val text = "¿Puedes [pick me up] a las 8? Tengo que drop off las llaves y ir a [the market]."

    @Test
    fun bracketedGapsIncludeTheirBrackets() {
        val gaps = WriterGaps.bracketed(text)
        assertEquals(listOf("[pick me up]", "[the market]"), gaps.map { text.substring(it.start, it.end) })
        assertTrue(WriterGaps.bracketed("[] [ ] [8]").isEmpty())
    }

    @Test
    fun theTokenizerSkipsBracketedGaps() {
        val forms = WordTokenizer.tokenize(text).map { it.form }
        assertEquals(listOf("puedes", "a", "las", "tengo", "que", "drop", "off", "las", "llaves", "y", "ir", "a"), forms)
    }

    @Test
    fun conjugationsSkipBracketedGaps() {
        val words = ConjugationMap.tokens("Tengo que [pick up] las llaves", "es").map { it.text }
        assertEquals(listOf("tengo", "que", "las", "llaves"), words)
    }

    @Test
    fun aFilledUnbracketedEnglishGapIsNeitherKnownNorTried() {
        val dropOff = text.indexOf("drop off")
        val filled = Utterance("check:a", "check", "es", text, listOf(Span(dropOff, dropOff + "drop off".length)), 1)
        val es = WordIndex.build(listOf(filled), Lemmatizer.Identity, 2).language("es")!!
        assertNull(es.word("drop"))
        assertNull(es.word("off"))
        assertNull(es.word("pick"))
        assertNull(es.word("me"))
        assertEquals(1, es.word("llaves")!!.right)
    }

    @Test
    fun englishWordsOutsideCorrectedSpansStillCount() {
        // The writer's own words are never guessed away: only a corrected span is read as a filled gap.
        val own = Utterance("check:b", "check", "es", "Vamos al pub", emptyList(), 1)
        assertNotNull(WordIndex.build(listOf(own), Lemmatizer.Identity, 2).language("es")!!.word("pub"))
        assertFalse(WriterGaps.isEnglishOnly("me"))
        assertTrue(WriterGaps.isEnglishOnly("drop"))
    }
}
