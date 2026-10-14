package com.evanaronson.linguize.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicRewordingsTest {
    private fun revision(original: String, corrected: String = original, natural: String,
        reported: List<VerdictChange> = listOf(VerdictChange(corrected, natural, "More natural")),
        fixes: List<VerdictChange> = emptyList()) =
        (interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = corrected != original,
            corrected = corrected, fixes = fixes, moreNatural = true, natural = natural, naturalChanges = reported))
            as CheckResult.Reviewed).revision

    @Test fun theScreenshotsOpaqueCorrectionIsWithheldWithoutInventingVerbFragments() {
        val original = "Bon Elena. Voldria saber que pensés de aquest vol"
        val corrected = "Bon Elena. Voldria saber què pensis d'aquest vol"
        val result = interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
            corrected = corrected, moreNatural = true, natural = "Hola, Elena. Voldria saber què penses d'aquest vol",
            naturalChanges = listOf(VerdictChange("Bon", "Hola,"), VerdictChange("pensis", "penses")))) as CheckResult.Reviewed
        assertEquals(setOf(EditKind.Fix, EditKind.Natural), result.withheld)
        assertEquals(listOf("que" to "què"), result.revision.edits.map { it.from to it.replacement })
        assertEquals("Bon Elena. Voldria saber què pensés de aquest vol", result.revision.acceptAll(EditKind.Fix).workingText)
        assertEquals(original, result.revision.acceptAll(EditKind.Natural).workingText)
        assertFalse(result.looksGood)
    }

    @Test fun aCorroboratedVerbInflectionDoesNotConsumeTheFollowingPreposition() {
        val original = "Bon Elena. Voldria saber que pensés d'aquest vol"
        val corrected = "Bon Elena. Voldria saber què pensis d'aquest vol"
        val r = revision(original, corrected, "Hola, Elena. Voldria saber què penses d'aquest vol",
            listOf(VerdictChange("Bon", "Hola,"), VerdictChange("pensis", "penses")),
            fixes = listOf(VerdictChange("que", "què"), VerdictChange("pensés", "pensis")))
        val verbFix = r.edits(EditKind.Fix).single { it.replacement == "pensis" }
        assertEquals("pensés", verbFix.from)
        assertEquals("Bon Elena. Voldria saber que pensis d'aquest vol", r.accept(verbFix.id).workingText)
        val verbRewording = r.edits(EditKind.Natural).single { it.replacement == "penses" }
        assertEquals("pensés", verbRewording.from)
        assertEquals("Bon Elena. Voldria saber que penses d'aquest vol", r.accept(verbRewording.id).workingText)
        assertEquals(corrected, r.acceptAll(EditKind.Fix).workingText)
    }

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

    @Test fun aReportedBundleCannotIncludeANewSentenceBoundary() {
        val original = "vull prendre una dutxa, després et truco"
        val natural = "em vull dutxar. Després et truco."
        val result = interpret(original, Verdict(status = Verdict.Status.Ok, moreNatural = true,
            natural = natural, naturalChanges = listOf(VerdictChange(original, natural)))) as CheckResult.Reviewed
        assertTrue(result.revision.edits(EditKind.Natural).isEmpty())
        assertTrue(EditKind.Natural in result.withheld)
        assertEquals(original, result.revision.acceptAll(EditKind.Natural).workingText)
    }

    @Test fun anUnreportedRewriteDoesNotGetInventedFallbackEdits() {
        val result = interpret("Vaig a la bodega", Verdict(status = Verdict.Status.Ok, moreNatural = true,
            natural = "Vaig a la botiga")) as CheckResult.Reviewed
        assertTrue(result.revision.edits(EditKind.Natural).isEmpty())
        assertTrue(EditKind.Natural in result.withheld)
    }

    @Test fun coincidentLettersCannotMakeIndependentWordSubstitutionsOneDecision() {
        for ((original, natural) in listOf("small cat" to "little feline", "fast car" to "quick automobile")) {
            val result = interpret(original, Verdict(status = Verdict.Status.Ok, moreNatural = true,
                natural = natural, naturalChanges = listOf(VerdictChange(original, natural, "Different expression"))))
                as CheckResult.Reviewed
            assertTrue(result.revision.edits(EditKind.Natural).isEmpty())
            assertTrue(EditKind.Natural in result.withheld)
        }
    }

    @Test fun separateInsertionsCannotRideAlongWithAnotherWordChoice() {
        for ((original, natural) in listOf(
            "Voy a viajar" to "No voy a volar",
            "Voy al parque" to "No voy al parque mañana",
        )) {
            val result = interpret(original, Verdict(status = Verdict.Status.Ok, moreNatural = true,
                natural = natural, naturalChanges = listOf(VerdictChange(original, natural)))) as CheckResult.Reviewed
            assertTrue(result.revision.edits(EditKind.Natural).isEmpty())
            assertTrue(EditKind.Natural in result.withheld)
            assertEquals(original, result.revision.acceptAll(EditKind.Natural).workingText)
        }
    }

    @Test fun unrelatedAccentCaseOrWhitespaceCannotHideInARewording() {
        for (original in listOf(
            "El cafe es bueno.", "el café es bueno.",
            "El café  es bueno.", "El café\nes bueno.", "El café\u00a0es bueno.",
        )) {
            val natural = "El café es excelente."
            val result = interpret(original, Verdict(status = Verdict.Status.Ok, moreNatural = true,
                natural = natural, naturalChanges = listOf(VerdictChange(original, natural)))) as CheckResult.Reviewed
            assertTrue(result.revision.edits.isEmpty())
            assertEquals(setOf(EditKind.Natural), result.withheld)
            assertFalse(result.looksGood)
        }
    }

    @Test fun anAlreadyCorrectedAccentStaysIndependentOfARewording() {
        val original = "El cafe es bueno."
        val corrected = "El café es bueno."
        val natural = "El café es excelente."
        val r = revision(original, corrected, natural)
        val edit = r.edits(EditKind.Natural).single()
        assertEquals("bueno", edit.from)
        assertTrue(edit.includes.isEmpty())
        assertEquals("El cafe es excelente.", r.accept(edit.id).workingText)
        assertEquals(natural, r.accept(edit.id).acceptAll(EditKind.Fix).workingText)
    }

    @Test fun discardedNonlexicalProposalsCannotReportACleanResult() {
        for ((original, natural) in listOf(
            "El cafe es bueno." to "El café es bueno.",
            "El café\nestá bueno." to "El café está bueno.",
            "estic bé" to "Estic bé",
            "estic bé" to "estic bé.",
            "estic bé" to "Estic bé.",
        )) {
            val result = interpret(original, Verdict(status = Verdict.Status.Ok, moreNatural = true,
                natural = natural, naturalChanges = listOf(VerdictChange(original, natural)))) as CheckResult.Reviewed
            assertTrue(result.revision.edits.isEmpty())
            assertEquals(setOf(EditKind.Natural), result.withheld)
            assertFalse(result.looksGood)
            assertFalse(result.isResolved)
        }
    }

    @Test fun aDiscardedAccentProposalKeepsTheJudgmentIncompleteAfterAnotherRewording() {
        val result = interpret("El cafe es bueno.", Verdict(status = Verdict.Status.Ok, moreNatural = true,
            natural = "El café es excelente.", naturalChanges = listOf(
                VerdictChange("cafe", "café"), VerdictChange("bueno", "excelente")))) as CheckResult.Reviewed
        val edit = result.revision.edits.single()
        assertEquals("bueno", edit.from)
        val accepted = result.copy(revision = result.revision.accept(edit.id))
        assertEquals("El cafe es excelente.", accepted.revision.workingText)
        assertEquals(setOf(EditKind.Natural), accepted.withheld)
        assertFalse(accepted.isResolved)
    }

    @Test fun canonicallyEquivalentTargetsRemainNoOps() {
        val original = "El cafe\u0301 es bueno."
        val result = interpret(original, Verdict(status = Verdict.Status.Ok, moreNatural = true,
            natural = "El café es bueno.")) as CheckResult.Reviewed
        assertTrue(result.revision.edits.isEmpty())
        assertTrue(result.withheld.isEmpty())
        assertTrue(result.looksGood)
        assertEquals(original, result.revision.workingText)
    }

    @Test fun independentChangesWithinOneSentenceHaveSeparateAcceptActions() {
        val original = "tinc algunes coses en una bodega"
        val natural = "tinc unes coses en una botiga"
        val r = revision(original, natural = natural,
            reported = listOf(VerdictChange("algunes", "unes"), VerdictChange("bodega", "botiga")))
        val changes = r.edits(EditKind.Natural)
        assertEquals(2, changes.size)
        assertEquals("tinc unes coses en una bodega", r.accept(changes.first().id).workingText)
        assertEquals(natural, r.acceptAll(EditKind.Natural).workingText)
        assertEquals(original, r.acceptAll(EditKind.Natural).undo().workingText)
    }

    @Test fun explicitChangesKeepExistingSentenceBoundaries() {
        for (separator in listOf(". ", "? ", "! ", "\n\n")) {
            val original = "vull prendre una dutxa" + separator + "tinc algunes coses"
            val natural = "vull dutxar-me" + separator + "tinc unes coses"
            val r = revision(original, natural = natural, reported = listOf(
                VerdictChange("prendre una dutxa", "dutxar-me"), VerdictChange("algunes", "unes")))
            val boundary = original.indexOf(separator)
            assertTrue(r.edits(EditKind.Natural).all { it.end <= boundary || it.start >= boundary + separator.length })
            assertEquals(natural, r.acceptAll(EditKind.Natural).workingText)
        }
    }

    @Test fun changesOnBothSidesOfAnUnchangedWordAreWithheld() {
        for ((original, natural) in listOf(
            "Voy a tomar una ducha" to "Me voy a duchar",
            "Él ayer ha dejado las llaves" to "Ayer dejó las llaves",
        )) {
            val result = interpret(original, Verdict(status = Verdict.Status.Ok, moreNatural = true,
                natural = natural, naturalChanges = listOf(VerdictChange(original, natural)))) as CheckResult.Reviewed
            assertTrue(result.revision.edits(EditKind.Natural).isEmpty())
            assertTrue(EditKind.Natural in result.withheld)
            assertFalse(result.looksGood)
            assertEquals(original, result.revision.acceptAll(EditKind.Natural).workingText)
        }
    }

    @Test fun aPhraseCanChangeSeveralWordsAsOneContiguousConstruction() {
        val original = "Voy a tomar una ducha"
        val natural = "Voy a ducharme"
        val r = revision(original, natural = natural)
        assertEquals(1, r.edits(EditKind.Natural).size)
        assertEquals(natural, r.acceptAll(EditKind.Natural).workingText)
    }

    @Test fun anInsertedPhraseKeepsItsBoundarySpacesInTheSameRegion() {
        val original = "Voy hoy."
        val natural = "Voy a ducharme hoy."
        val r = revision(original, natural = natural)
        val edit = r.edits(EditKind.Natural).single()
        assertEquals("", edit.from)
        assertEquals("a ducharme ", edit.replacement)
        assertEquals(natural, r.accept(edit.id).workingText)
        assertEquals(original, r.accept(edit.id).undo().workingText)
    }
}
