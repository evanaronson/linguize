package com.evanaronson.linguize.core

import org.junit.Assert.*
import org.junit.Test

class AssumptionLinksTest {
    private val assumptions = listOf(Assumption("Who?", "I", alternatives = listOf("You")),
        Assumption("When?", "Tomorrow", alternatives = listOf("Today")))

    @Test fun dependenciesFollowTheirOwnDecisionThroughFixMappingAndNaturalWidening() {
        val verdict = Verdict(Verdict.Status.Ok, hasErrors = true, corrected = "estàs amb pluja",
            assumptions = assumptions,
            fixes = listOf(VerdictChange("estas", "estàs", confidence = 3, impact = 1, assumptionIndices = listOf(0))),
            moreNatural = true, natural = "portes pluja",
            naturalChanges = listOf(VerdictChange("estàs amb", "portes", confidence = 1, impact = 2,
                assumptionIndices = listOf(1))))
        val result = interpret("estas amb pluja", verdict) as CheckResult.Reviewed
        assertEquals(listOf(0), result.revision.edits.single { it.kind == EditKind.Fix }.assumptionIndices)
        val natural = result.revision.edits.single { it.kind == EditKind.Natural }
        assertEquals(listOf(1), natural.assumptionIndices)
        assertEquals("estas amb", natural.from)
        assertEquals(setOf(0), natural.includes)
        val withoutLinks = interpret("estas amb pluja", verdict.copy(
            fixes = verdict.fixes.map { it.copy(assumptionIndices = emptyList()) },
            naturalChanges = verdict.naturalChanges.map { it.copy(assumptionIndices = emptyList()) })) as CheckResult.Reviewed
        assertEquals(withoutLinks.revision.edits, result.revision.edits.map { it.copy(assumptionIndices = emptyList()) })
        assertEquals("portes pluja", result.revision.acceptAll(EditKind.Natural).workingText)
    }

    @Test fun filteringAnInvalidAssumptionCannotRetargetReferencesToAnotherReading() {
        val verdict = Verdict(Verdict.Status.Ok, hasErrors = true, corrected = "estàs",
            assumptions = listOf(Assumption("", "Invalid"), assumptions[0]),
            fixes = listOf(VerdictChange("estas", "estàs", assumptionIndices = listOf(0, 1, -1, 99, 1))))
        val result = interpret("estas", verdict) as CheckResult.Reviewed
        assertEquals(listOf(assumptions[0]), result.assumptions)
        assertEquals(listOf(0), result.revision.edits.single().assumptionIndices)
        val invalidOnly = interpret("estas", verdict.copy(fixes = listOf(
            verdict.fixes.single().copy(assumptionIndices = listOf(0, 99))))) as CheckResult.Reviewed
        assertTrue(invalidOnly.revision.edits.single().assumptionIndices.isEmpty())
    }

    @Test fun splittingOrInferringRepairsDoesNotInventWhichPartDependsOnAnAssumption() {
        val aggregate = listOf(VerdictChange("estas despres", "estàs després", assumptionIndices = listOf(0)))
        assertTrue(Edits.fixes("estas despres", "estàs després", aggregate).edits.all { it.assumptionIndices.isEmpty() })
        val incomplete = listOf(VerdictChange("estas", "estàs", assumptionIndices = listOf(0)))
        assertTrue(Edits.fixes("estas despres", "estàs després", incomplete).edits.all { it.assumptionIndices.isEmpty() })
        val grouped = Edits.fixes("hola. Adeu", "hola adeu", listOf(
            VerdictChange(".", "", assumptionIndices = listOf(0)),
            VerdictChange("Adeu", "adeu", assumptionIndices = listOf(1))))
        assertTrue(grouped.edits.single().assumptionIndices.isEmpty())
    }

    @Test fun separateDecisionsCanShareTheSameMeaningDependency() {
        val edits = Edits.fixes("estas despres", "estàs després", listOf(
            VerdictChange("estas", "estàs", assumptionIndices = listOf(0, 1)),
            VerdictChange("despres", "després", assumptionIndices = listOf(0))))
        assertEquals(listOf(listOf(0, 1), listOf(0)), edits.edits.map { it.assumptionIndices })
    }
}
