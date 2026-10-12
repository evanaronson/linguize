package com.evanaronson.linguize.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputStringsTest {
    private fun revision(original: String, corrected: String = "", natural: String = "",
        changes: List<VerdictChange> = emptyList()): Revision =
        (interpret(original, Verdict(status = Verdict.Status.Ok,
            hasErrors = corrected.isNotEmpty(), corrected = corrected,
            moreNatural = natural.isNotEmpty(), natural = natural, naturalChanges = changes))
            as CheckResult.Reviewed).revision

    @Test fun spacingOnlyRepairsSurvivePreviewAcceptUndoAndSelectionWriteback() {
        for (line in listOf("\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029")) {
            val original = "Bon" + line + "dia"
            val r = revision(original, corrected = "Bon dia")
            assertTrue(line, r.edits.isNotEmpty())
            assertEquals("Bon dia", r.preview(EditKind.Fix).text)
            val accepted = r.acceptAll(EditKind.Fix)
            assertEquals("Bon dia", accepted.workingText)
            assertEquals(original, accepted.undo().workingText)
            val selection = Selection.of("Before " + original + " after", 7, 7 + original.length)
            assertEquals("Before Bon dia after", selection.with(accepted.workingText))
            for (edit in r.edits) {
                assertTrue("A spacing fix needs a visible highlight", edit.replacement.any { !it.isWhitespace() })
                assertTrue(r.preview(EditKind.Fix).ranges.containsKey(edit.id))
                assertEquals(edit.from, original.substring(edit.start, edit.end))
            }
        }
    }

    @Test fun lineBreakDeletedAfterPunctuationIsNotLost() {
        val r = revision("Hola,\n amic", corrected = "Hola, amic")
        assertEquals("Hola, amic", r.acceptAll(EditKind.Fix).workingText)
    }

    @Test fun breaksInsideWordsAndMixedCorrectionsKeepOffsets() {
        val original = "Com es\r\ntas amb la\npluja? 😀"
        val corrected = "Com estàs amb la pluja? 😀"
        val r = revision(original, corrected)
        assertEquals(corrected, r.acceptAll(EditKind.Fix).workingText)
        assertEquals(original, r.acceptAll(EditKind.Fix).undo().workingText)
        for (edit in r.edits) {
            assertEquals(edit.from, original.substring(edit.start, edit.end))
            assertFalse(original.getOrNull(edit.start - 1) == '\r' && original.getOrNull(edit.start) == '\n')
            assertFalse(original.getOrNull(edit.end - 1) == '\r' && original.getOrNull(edit.end) == '\n')
        }
    }

    @Test fun deliberatelyUnchangedParagraphsAndListsStayUnchanged() {
        for (original in listOf("Bon dia.\n\nCom estàs?", "- pa\n- llet", "Bon dia.\r\n\r\nCom estàs?")) {
            val r = revision(original, corrected = original)
            assertTrue(r.edits.isEmpty())
            assertEquals(original, r.workingText)
        }
    }

    @Test fun oneReportedRewriteOfTwoSentencesBecomesIndependentEdits() {
        val original = "Voy a tomar una ducha. Voy a hacer una llamada."
        val natural = "Me voy a duchar. Voy a llamar."
        val r = revision(original, natural = natural,
            changes = listOf(VerdictChange(original, natural, "More usual")))
        val firstSentenceEnd = original.indexOf(". ") + 2
        val first = r.edits(EditKind.Natural).filter { it.start < firstSentenceEnd }
        val second = r.edits(EditKind.Natural).filter { it.start >= firstSentenceEnd }
        assertTrue(first.isNotEmpty())
        assertTrue(second.isNotEmpty())
        assertTrue(r.edits.all { it.end <= firstSentenceEnd || it.start >= firstSentenceEnd })
        val firstOnly = first.fold(r) { current, edit -> current.accept(edit.id) }
        assertEquals("Me voy a duchar. Voy a hacer una llamada.", firstOnly.workingText)
        assertEquals(natural, firstOnly.acceptAll(EditKind.Natural).workingText)
        assertEquals(original, r.acceptAll(EditKind.Natural).undo().workingText)
    }

    @Test fun mergingTwoSentencesIsRejectedWithOrWithoutReportedChanges() {
        val original = "Voy a tomar una ducha. Te llamo después."
        val merged = "Me voy a duchar y luego te llamo."
        for (reported in listOf(emptyList(), listOf(VerdictChange(original, merged)))) {
            val r = revision(original, natural = merged, changes = reported)
            assertTrue(r.edits(EditKind.Natural).isEmpty())
            assertEquals(original, r.acceptAll(EditKind.Natural).workingText)
        }
    }

    @Test fun sentenceBoundariesStillApplyToLowercaseQuestionsAndQuotes() {
        for ((original, merged) in listOf(
            "voy a tomar una ducha. te llamo después." to "me voy a duchar y te llamo.",
            "Com estàs? Què fas?" to "Com et va tot i què fas?",
            "Com estàs?Què fas?" to "Com et va tot i què fas?",
            "Voy a ducharme.Te llamo." to "Voy a ducharme y te llamo.",
            "Va dir «Bon dia!». Va marxar." to "Va saludar i va marxar.",
            "Bon dia\n\nCom estàs" to "Bon dia, com et va tot",
        )) {
            assertTrue(original, revision(original, natural = merged).edits(EditKind.Natural).isEmpty())
        }
    }

    @Test fun aRewordingAndLineBreakFixCanBeAcceptedInEitherOrder() {
        val original = "Voy a tomar\r\nuna ducha. Te llamo despues."
        val corrected = "Voy a tomar una ducha. Te llamo después."
        val natural = "Me voy a duchar. Te llamo después."
        val r = revision(original, corrected, natural,
            listOf(VerdictChange("Voy a tomar una ducha", "Me voy a duchar")))
        for (kinds in listOf(listOf(EditKind.Fix, EditKind.Natural), listOf(EditKind.Natural, EditKind.Fix))) {
            assertEquals(natural, kinds.fold(r) { current, kind -> current.acceptAll(kind) }.workingText)
        }
        assertEquals("Me voy a duchar. Te llamo despues.", r.acceptAll(EditKind.Natural).workingText)
    }

    @Test fun singleCrlfAbbreviationsAndDecimalsDoNotInventSentenceBoundaries() {
        for (text in listOf("Bon\r\ndia", "El Dr. Garcia arriba.", "Costa 3.5 euros.", "Espera... que vinc.", "Viu als EE.UU.", "Mira https://example.com.")) {
            assertEquals(text, 1, Sentences.ranges(text).size)
        }
        assertEquals(2, Sentences.ranges("Bon dia. com estàs?").size)
        assertEquals(2, Sentences.ranges("Bon dia\r\n\r\nCom estàs").size)
    }
}
