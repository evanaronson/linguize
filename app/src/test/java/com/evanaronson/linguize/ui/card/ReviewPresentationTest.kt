package com.evanaronson.linguize.ui.card

import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Assumption
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.SuggestionAssessment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPresentationTest {
    @Test fun duplicateQuestionLabelsStayReadableButCannotPromiseAClarificationAction() {
        val assumptions = listOf(Assumption("Who?", "I", alternatives = listOf("You")),
            Assumption("Who?", "Someone else", alternatives = listOf("A friend")))
        val entry = understandingEntry("Meaning", assumptions, emptyList(), false)
        assertTrue(entry.visible)
        assertFalse(entry.canClarify)
        assertEquals(0, entry.unresolved)
        assertTrue(linkedAssumptions(listOf(0, 1), assumptions, emptyList()).isEmpty())
    }
    @Test fun inlineDependenciesOnlyShowExplicitUnresolvedReferencesWithoutInventingLinks() {
        val assumptions = listOf(Assumption("Who?", "I", alternatives = listOf("You")),
            Assumption("When?", "Tomorrow", alternatives = listOf("Today")))
        assertTrue(linkedAssumptions(emptyList(), assumptions, emptyList()).isEmpty())
        assertEquals(assumptions, linkedAssumptions(listOf(0, 1, 0, -1, 2), assumptions, emptyList()))
        assertEquals(listOf(assumptions[1]), linkedAssumptions(listOf(0, 1), assumptions, listOf(Settled("Who?", "I"))))
        assertTrue(linkedAssumptions(listOf(0), emptyList(), emptyList()).isEmpty())
        assertTrue(linkedAssumptions(listOf(0), listOf(assumptions[0], assumptions[0].copy(assumed = "Someone else")), emptyList()).isEmpty())
    }

    @Test fun clarificationCanConfirmTheCurrentReadingOrChooseADistinctAlternative() {
        val assumption = Assumption("Who?", "I", alternatives = listOf("You", "I", "", "You"))
        assertEquals(listOf("I", "You"), assumptionChoices(assumption))
    }

    @Test
    fun missingAssessmentIsUnassessedWhileZeroIsAnActualRating() {
        val missing = assessmentPresentation(null)
        assertNull(missing.confidence.score)
        assertNull(missing.impact.score)
        assertEquals(R.string.assessment_unassessed, missing.confidence.label)
        assertEquals(R.string.assessment_unassessed, missing.impact.label)

        val zero = assessmentPresentation(SuggestionAssessment(0, 0))
        assertEquals(0, zero.confidence.score)
        assertEquals(0, zero.impact.score)
        assertEquals(R.string.confidence_unsupported, zero.confidence.label)
        assertEquals(R.string.impact_none, zero.impact.label)
    }

    @Test
    fun confidenceAndImpactKeepTheirIndependentLabels() {
        val scored = assessmentPresentation(SuggestionAssessment(3, 1))
        assertEquals(3, scored.confidence.score)
        assertEquals(R.string.confidence_strong, scored.confidence.label)
        assertEquals(1, scored.impact.score)
        assertEquals(R.string.impact_minor, scored.impact.label)
    }

    @Test
    fun liveEntryOnlyCountsUnresolvedAssumptions() {
        val assumptions = listOf(
            Assumption("Who?", "I", alternatives = listOf("You")),
            Assumption("Activity?", "Driving", alternatives = listOf("Walking")),
        )
        val entry = understandingEntry("", assumptions, listOf(Settled("Who?", "You")), false)
        assertTrue(entry.visible)
        assertTrue(entry.canClarify)
        assertEquals(1, entry.unresolved)
    }

    @Test
    fun settledAssumptionsRemainReadableWithoutAskingForMoreClarification() {
        val entry = understandingEntry("", listOf(Assumption("Who?", "I")), listOf(Settled("Who?", "You")), false)
        assertTrue(entry.visible)
        assertFalse(entry.canClarify)
        assertEquals(0, entry.unresolved)
    }

    @Test
    fun historyNeverPromisesActionableClarification() {
        val entry = understandingEntry("Meaning", listOf(Assumption("Who?", "I", alternatives = listOf("You"))), emptyList(), true)
        assertTrue(entry.visible)
        assertFalse(entry.canClarify)
        assertEquals(1, entry.unresolved)
    }

    @Test
    fun assumptionsWithoutAlternativesRemainReadableWithoutOfferingClarification() {
        val entry = understandingEntry("", listOf(Assumption("Who?", "I")), emptyList(), false)
        assertTrue(entry.visible)
        assertFalse(entry.canClarify)
        assertEquals(0, entry.unresolved)
    }

    @Test
    fun clarificationCountExcludesNonActionableAndAlreadySettledAssumptions() {
        val assumptions = listOf(
            Assumption("Who?", "I", alternatives = listOf("You")),
            Assumption("Activity?", "Driving", alternatives = listOf("Walking")),
            Assumption("When?", "Tomorrow"),
        )
        val entry = understandingEntry("", assumptions, listOf(Settled("Who?", "You")), false)
        assertTrue(entry.canClarify)
        assertEquals(1, entry.unresolved)
    }

    @Test
    fun meaningAloneRemainsReadableAndEmptyEntryStaysHidden() {
        assertTrue(understandingEntry("Meaning", emptyList(), emptyList(), false).visible)
        assertFalse(understandingEntry("Meaning", emptyList(), emptyList(), false).canClarify)
        assertFalse(understandingEntry("", emptyList(), emptyList(), false).visible)
    }
}
