package com.evanaronson.linguize.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SuggestionAssessmentTest {
    @Test fun zeroIsARealAssessmentAndLegacyNullIsNotZero() {
        val unassessed = Json.decodeFromString<VerdictChange>("""{"from":"a","to":"b","why":"Example"}""")
        assertNull(unassessed.assessment)
        val assessed = Json.decodeFromString<VerdictChange>("""{"from":"a","to":"b","confidence":0,"impact":0}""")
        assertEquals(SuggestionAssessment(0, 0), assessed.assessment)
        assertEquals(assessed, Json.decodeFromString<VerdictChange>(Json.encodeToString(assessed)))
    }

    @Test fun bothScoresAreRequiredTogetherAndMustBeInRange() {
        for ((confidence, impact) in listOf(null to 0, 0 to null, -1 to 0, 4 to 0, 0 to -1, 0 to 4)) {
            assertThrows(IllegalArgumentException::class.java) {
                VerdictChange(confidence = confidence, impact = impact)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { SuggestionAssessment(4, 0) }
        assertThrows(IllegalArgumentException::class.java) { SuggestionAssessment(0, -1) }
    }

    @Test fun scoresFollowTrimmedAndRepeatedExactReportedDecisions() {
        val edits = Edits.fixes("estas estas", "estàs estás", listOf(
            VerdictChange("estas", "estàs", "Accent", 3, 1),
            VerdictChange("estas", "estás", "Other accent", 2, 2),
        )).edits
        assertEquals(listOf(SuggestionAssessment(3, 1), SuggestionAssessment(2, 2)), edits.map { it.assessment })
        val trimmed = Edits.fixes("ara tinc que usar això", "ara he d'usar això", listOf(
            VerdictChange("ara tinc que usar això", "ara he d'usar això", "Obligation", 3, 2),
        )).edits.single()
        assertEquals("tinc que usar", trimmed.from)
        assertEquals(SuggestionAssessment(3, 2), trimmed.assessment)
    }

    @Test fun mechanicalSplitsDoNotInventIndividualAssessmentsFromAnAggregate() {
        val edits = Edits.fixes("estas despres", "estàs després", listOf(
            VerdictChange("estas despres", "estàs després", "Accents", 3, 1),
        )).edits
        assertEquals(2, edits.size)
        assertTrue(edits.all { it.assessment == null })
        val separate = Edits.fixes("estas despres", "estàs després", listOf(
            VerdictChange("estas", "estàs", "Accent", 3, 1),
            VerdictChange("despres", "després", "Accent", 2, 2),
        )).edits
        assertEquals(listOf(SuggestionAssessment(3, 1), SuggestionAssessment(2, 2)), separate.map { it.assessment })
        assertEquals(edits.map { it.copy(assessment = null, why = null) },
            separate.map { it.copy(assessment = null, why = null) })
    }

    @Test fun boundaryCaseGroupingRetainsOneDecisionButNeverInventsACombinedScore() {
        val original = "hola. Adeu"
        val corrected = "hola adeu"
        val one = Edits.fixes(original, corrected, listOf(
            VerdictChange(". A", " a", "Join", 2, 1),
        )).edits
        // A partial lexical report is invalid and cannot donate its score to fallback repairs.
        assertTrue(one.all { it.assessment == null })
        val grouped = Edits.fixes(original, corrected, listOf(
            VerdictChange(".", "", "Stop", 3, 1),
            VerdictChange("Adeu", "adeu", "Case", 2, 2),
        )).edits.single()
        assertNull(grouped.assessment)
        val complete = Edits.fixes(original, corrected, listOf(
            VerdictChange(". Adeu", " adeu", "Boundary", 2, 1),
        )).edits.single()
        assertEquals(SuggestionAssessment(2, 1), complete.assessment)
    }

    @Test fun unmatchedInferredRepairsDoNotBorrowScoresFromInvalidReports() {
        val edits = Edits.fixes("estas despres", "estàs després", listOf(
            VerdictChange("estas", "estàs", "Accent", 3, 1),
        )).edits
        assertEquals(2, edits.size)
        assertTrue(edits.all { it.assessment == null })
    }

    @Test fun rewordingKeepsItsOwnScoreWhenItIncludesAFix() {
        val fixes = Edits.fixes("estas amb pluja", "estàs amb pluja", listOf(
            VerdictChange("estas", "estàs", "Accent", 3, 1),
        )).edits
        val natural = Edits.naturalResult("estas amb pluja", fixes, "portes pluja", listOf(
            VerdictChange("estàs amb", "portes", "Expression", 1, 2),
        ), firstId = fixes.size).edits.single()
        assertEquals(setOf(fixes.single().id), natural.includes)
        assertEquals(SuggestionAssessment(1, 2), natural.assessment)
        assertEquals(SuggestionAssessment(3, 1), fixes.single().assessment)
    }

    @Test fun scoresNeverChangeWhichEditsAreOfferedOrAccepted() {
        val original = "estas amb pluja"
        var baseline: Revision? = null
        for (confidence in 0..3) for (impact in 0..3) {
            val verdict = Verdict(status = Verdict.Status.Ok, hasErrors = true,
                corrected = "estàs amb pluja",
                fixes = listOf(VerdictChange("estas", "estàs", "Accent", confidence, impact)),
                moreNatural = true, natural = "portes pluja",
                naturalChanges = listOf(VerdictChange("estàs amb", "portes", "Expression", confidence, impact)))
            val result = interpret(original, verdict) as CheckResult.Reviewed
            assertTrue(result.withheld.isEmpty())
            val revision = result.revision
            if (baseline == null) baseline = revision
            assertEquals(baseline.edits.map { it.copy(assessment = null) }, revision.edits.map { it.copy(assessment = null) })
            assertEquals("estàs amb pluja", revision.acceptAll(EditKind.Fix).workingText)
            assertEquals("portes pluja", revision.acceptAll(EditKind.Natural).workingText)
            assertEquals(original, revision.acceptAll(EditKind.Natural).undo().workingText)
        }
    }

}
