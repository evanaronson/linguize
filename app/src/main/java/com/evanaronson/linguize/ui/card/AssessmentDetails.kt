package com.evanaronson.linguize.ui.card

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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

@Composable
internal fun AssessmentDetails(assessment: SuggestionAssessment?) {
    val presentation = assessmentPresentation(assessment)
    AssessmentText(R.string.assessment_confidence, presentation.confidence)
    AssessmentText(R.string.assessment_impact, presentation.impact)
}

@Composable
private fun AssessmentText(@StringRes dimension: Int, line: AssessmentLine) {
    val value = if (line.score == null) stringResource(line.label)
        else stringResource(R.string.assessment_score, line.score, stringResource(line.label))
    Text(
        stringResource(R.string.assessment_dimension, stringResource(dimension), value),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
