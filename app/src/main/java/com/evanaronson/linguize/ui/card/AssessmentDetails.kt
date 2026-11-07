package com.evanaronson.linguize.ui.card

import androidx.annotation.StringRes
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.SuggestionAssessment

/** An absent historical assessment must never look like an assessed zero. */
internal data class AssessmentLine(val level: Int?, @param:StringRes val label: Int)
internal data class AssessmentPresentation(val confidence: AssessmentLine, val impact: AssessmentLine)

/** Both meters use the same display buckets. */
private fun assessmentLevel(score: Int): Int = when (score) {
    in 0..5 -> 1
    in 6..8 -> 2
    else -> 3
}

internal fun assessmentPresentation(assessment: SuggestionAssessment?): AssessmentPresentation {
    if (assessment == null) {
        val unassessed = AssessmentLine(null, R.string.assessment_unassessed)
        return AssessmentPresentation(unassessed, unassessed)
    }
    val levels = listOf(R.string.assessment_low, R.string.assessment_medium, R.string.assessment_high)
    fun line(score: Int): AssessmentLine {
        val level = assessmentLevel(score)
        return AssessmentLine(level, levels[level - 1])
    }
    return AssessmentPresentation(
        line(assessment.confidence),
        line(assessment.impact),
    )
}
