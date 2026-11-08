package com.evanaronson.linguize.core.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GapsTest {
    private val source = "Hola, ¿puedes [pick me up] a las 8? Tengo que [drop off] las llaves."

    @Test fun bracketedPhrasesAreGapsAndEmptyBracketsAreNot() {
        val gaps = Gaps.marked("$source [ ] []")
        assertEquals(listOf("pick me up", "drop off"), gaps.map { it.phrase })
        assertEquals("[pick me up]", source.substring(gaps[0].start, gaps[0].end))
    }

    @Test fun bracketsAreOfferedByCountAndMixedEnglishIsOfferedOnlyWhenItIsAMinority() {
        assertEquals(GapHint.Marked(2), Gaps.hint(source))
        assertEquals(GapHint.Mixed, Gaps.hint("Hola, ¿puedes pick me up a las 8 de la tarde?"))
        // Correct Spanish or Catalan: nothing to offer.
        assertNull(Gaps.hint("Hola, ¿me puedes recoger a las 8? He comido."))
        assertNull(Gaps.hint("On és la casa? Has vist el meu email online aquest weekend?"))
        // A message in English is a translation, not a gap.
        assertNull(Gaps.hint("Can you pick me up at the station tomorrow?"))
        assertNull(Gaps.hint("Hola"))
    }

    @Test fun fillsArePlacedOnTheSourceAndTheWritersWordsAreKeptExactly() {
        val fill = GapFill.place(source, "Spanish", listOf(
            QuotedFill("[pick me up]", "recogerme", "Clitic joined to the infinitive.", listOf("venir a buscarme")),
            // Quoted without its brackets: the brackets go too.
            QuotedFill("drop off", "dejar", ""),
        ))
        assertEquals("Hola, ¿puedes recogerme a las 8? Tengo que dejar las llaves.", fill.text)
        assertEquals(listOf("pick me up", "drop off"), fill.fills.map { it.original })
        assertEquals(listOf(1, 3), fill.pieces.mapIndexedNotNull { index, piece -> index.takeIf { piece.fill != null } })
        val swapped = fill.choose(0, 1)
        assertEquals("Hola, ¿puedes venir a buscarme a las 8? Tengo que dejar las llaves.", swapped.text)
        assertEquals(listOf("recogerme"), swapped.fills[0].alternatives)
        assertEquals(swapped, swapped.choose(0, 9))
    }

    @Test fun aFillMayTakeTheWordNextToItWhenGrammarNeedsIt() {
        val fill = GapFill.place("Voy a [the market] mañana", "Spanish", listOf(QuotedFill("a [the market]", "al mercado")))
        assertEquals("Voy al mercado mañana", fill.text)
        assertEquals("a the market", fill.fills.single().original)
    }

    @Test fun unmarkedGapsAreFilledInPlace() {
        val fill = GapFill.place("Necesito un plumber para mañana", "Spanish", listOf(QuotedFill("plumber", "fontanero")))
        assertEquals("Necesito un fontanero para mañana", fill.text)
    }

    @Test fun answersThatWouldTouchOtherWordsOrSkipAGapAreRejected() {
        // Not in the source: the model rewrote the writer's words.
        assertThrows(IllegalArgumentException::class.java) {
            GapFill.place(source, "Spanish", listOf(QuotedFill("puede [pick me up]", "recogerme"), QuotedFill("[drop off]", "dejar")))
        }
        // Out of order.
        assertThrows(IllegalArgumentException::class.java) {
            GapFill.place(source, "Spanish", listOf(QuotedFill("[drop off]", "dejar"), QuotedFill("[pick me up]", "recogerme")))
        }
        // A marked gap left unfilled.
        assertThrows(IllegalArgumentException::class.java) {
            GapFill.place(source, "Spanish", listOf(QuotedFill("[pick me up]", "recogerme")))
        }
        // A fill that still has brackets, or none at all.
        assertThrows(IllegalArgumentException::class.java) {
            GapFill.place("Hola [friend]", null, listOf(QuotedFill("[friend]", "[amigo]")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GapFill.place("Hola [friend]", null, listOf(QuotedFill("[friend]", "  ")))
        }
    }

    @Test fun alternativesAreTrimmedDedupedAndCapped() {
        val fill = GapFill.place("Hola [friend]", " Spanish ", listOf(
            QuotedFill("[friend]", "amigo", "", listOf("amigo", " colega ", "", "tío", "majo", "compi")),
        ))
        assertEquals(listOf("amigo", "colega", "tío", "majo"), fill.fills.single().options)
        assertEquals("Spanish", fill.language)
    }

    @Test fun nothingToFillKeepsTheSource() {
        val fill = GapFill.place("Hola, ¿qué tal?", "", emptyList())
        assertEquals("Hola, ¿qué tal?", fill.text)
        assertNull(fill.language)
    }
}
