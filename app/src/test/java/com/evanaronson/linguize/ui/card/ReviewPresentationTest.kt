package com.evanaronson.linguize.ui.card

import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Assumption
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.SuggestionAssessment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPresentationTest {
    @Test fun duplicateQuestionLabelsBlockScopedAnswers() {
        val assumptions = listOf(Assumption("Who?", "I", alternatives = listOf("You")),
            Assumption("Who?", "Someone else", alternatives = listOf("A friend")))
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
        assertNull(missing.confidence.level)
        assertNull(missing.impact.level)
        assertEquals(R.string.assessment_unassessed, missing.confidence.label)
        assertEquals(R.string.assessment_unassessed, missing.impact.label)

        val zero = assessmentPresentation(SuggestionAssessment(0, 0))
        assertEquals(1, zero.confidence.level)
        assertEquals(1, zero.impact.level)
        assertEquals(R.string.assessment_low, zero.confidence.label)
        assertEquals(R.string.assessment_low, zero.impact.label)
    }

    @Test
    fun bothDimensionsUseTheSameDisplayBuckets() {
        val expectedLevels = listOf(1, 1, 1, 1, 1, 1, 2, 2, 2, 3, 3)
        val labels = listOf(R.string.assessment_low, R.string.assessment_medium, R.string.assessment_high)
        for (score in 0..10) {
            val impact = 10 - score
            val scored = assessmentPresentation(SuggestionAssessment(score, impact))
            assertEquals(expectedLevels[score], scored.confidence.level)
            assertEquals(expectedLevels[impact], scored.impact.level)
            assertEquals(labels[expectedLevels[score] - 1], scored.confidence.label)
            assertEquals(labels[expectedLevels[impact] - 1], scored.impact.label)
        }
    }

    @Test
    fun aMeaningClarificationDoesNotAnswerAnAssumptionWithTheSameLabel() {
        val assumptions = listOf(Assumption("Who?", "I", alternatives = listOf("You")))
        val meaning = Settled("Who?", "A friend", Settled.Scope.Meaning)
        assertEquals(assumptions, linkedAssumptions(listOf(0), assumptions, listOf(meaning)))
    }
}
