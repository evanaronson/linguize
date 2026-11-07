package com.evanaronson.linguize.ui.card

import androidx.annotation.StringRes
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.SuggestionAssessment

/** An absent historical assessment must never look like an assessed zero. */
internal data class AssessmentLine(val score: Int?, @param:StringRes val label: Int)
internal data class AssessmentPresentation(val confidence: AssessmentLine, val impact: AssessmentLine)

internal fun assessmentPresentation(assessment: SuggestionAssessment?): AssessmentPresentation {
    val confidenceLabels = listOf(R.string.confidence_unsupported, R.string.confidence_weak,
        R.string.confidence_moderate, R.string.confidence_strong)
    val impactLabels = listOf(R.string.impact_none, R.string.impact_minor,
        R.string.impact_substantial, R.string.impact_critical)
    return AssessmentPresentation(
        AssessmentLine(assessment?.confidence, assessment?.let { confidenceLabels[it.confidence] } ?: R.string.assessment_unassessed),
        AssessmentLine(assessment?.impact, assessment?.let { impactLabels[it.impact] } ?: R.string.assessment_unassessed),
    )
}
