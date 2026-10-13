package com.evanaronson.linguize.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicRewordingsTest {
    private fun revision(original: String, corrected: String = original, natural: String,
        reported: List<VerdictChange> = listOf(VerdictChange(corrected, natural, "More natural"))) =
        (interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = corrected != original,
            corrected = corrected, moreNatural = true, natural = natural, naturalChanges = reported))
            as CheckResult.Reviewed).revision

    @Test fun screenshotGreetingPreferenceAndFormattingDoNotBecomeRewordings() {
        val original = "Hola que tal estic ber"
        val corrected = "Hola que tal, estic bé"
        val r = revision(original, corrected, "Hola, com vas? Estic bé.")
        assertTrue(r.edits(EditKind.Natural).isEmpty())
        assertEquals(corrected, r.acceptAll(EditKind.Fix).workingText)
        assertEquals(original, r.acceptAll(EditKind.Natural).workingText)
    }

    @Test fun normalCatalanGreetingIsProtectedWithOrWithoutModelChangeLists() {
        for (original in listOf("què tal", "que tal", "Què tal?")) {
            val target = if (original.endsWith("?")) "Com vas?" else "com vas"
            for (changes in listOf(emptyList(), listOf(VerdictChange(original, target, "More natural greeting")))) {
                assertTrue(revision(original, natural = target, reported = changes).edits(EditKind.Natural).isEmpty())
            }
        }
    }

    @Test fun aReportedBundleCannotIncludePunctuationOrTheNextWordsCapitalisation() {
        val original = "vull prendre una dutxa, després et truco"
        val natural = "em vull dutxar. Després et truco."
        val r = revision(original, natural = natural)
        val edits = r.edits(EditKind.Natural)
        assertTrue(edits.isNotEmpty())
        assertTrue(edits.none { it.from.contains(",") || it.replacement.contains(".") || it.from.contains("després") })
        assertEquals("em vull dutxar, després et truco", r.acceptAll(EditKind.Natural).workingText)
    }

    @Test fun aPeriodOrCaseChangeAloneIsNotARewording() {
        for (target in listOf("Estic bé", "estic bé.", "Estic bé.")) {
            assertTrue(revision("estic bé", natural = target).edits(EditKind.Natural).isEmpty())
        }
    }

    @Test fun independentChangesWithinOneSentenceHaveSeparateAcceptActions() {
        val original = "tinc algunes coses en una bodega"
        val natural = "tinc unes coses en una botiga"
        val r = revision(original, natural = natural)
        val changes = r.edits(EditKind.Natural)
        assertEquals(2, changes.size)
        assertEquals("tinc unes coses en una bodega", r.accept(changes.first().id).workingText)
        assertEquals(natural, r.acceptAll(EditKind.Natural).workingText)
        assertEquals(original, r.acceptAll(EditKind.Natural).undo().workingText)
    }

    @Test fun existingSentenceBoundariesSurviveAReportedWholeTextRewrite() {
        for (separator in listOf(". ", "? ", "! ", "\n\n")) {
            val original = "vull prendre una dutxa" + separator + "tinc algunes coses"
            val natural = "em vull dutxar" + separator + "tinc unes coses"
            val r = revision(original, natural = natural)
            val boundary = original.indexOf(separator)
            assertTrue(r.edits(EditKind.Natural).all { it.end <= boundary || it.start >= boundary + separator.length })
            assertEquals(natural, r.acceptAll(EditKind.Natural).workingText)
        }
    }

    @Test fun aPhraseCanChangeSeveralWordsAndIncludeItsNecessaryInitialLowercase() {
        val original = "Voy a tomar una ducha"
        val natural = "Me voy a duchar"
        val r = revision(original, natural = natural)
        assertEquals(1, r.edits(EditKind.Natural).size)
        assertEquals(natural, r.acceptAll(EditKind.Natural).workingText)
    }
}
