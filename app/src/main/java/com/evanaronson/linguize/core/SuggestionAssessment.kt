package com.evanaronson.linguize.core

import kotlinx.serialization.Serializable

/** Version of the anchored, ordinal 0–10 rubric; not a probability or a filter threshold. */
const val SCORING_RUBRIC_VERSION = "confidence-impact-v3"

/** Assessment of this exact linguistic decision. Missing assessments are null, never zero. */
@Serializable
data class SuggestionAssessment(
    val confidence: Int,
    val impact: Int,
    val rubricVersion: String = SCORING_RUBRIC_VERSION,
    val origin: String = "generated",
) {
    init {
        require(confidence in 0..10) { "Confidence must be an integer from 0 to 10" }
        require(impact in 0..10) { "Impact must be an integer from 0 to 10" }
        require(rubricVersion.isNotBlank()) { "A scoring rubric version is required" }
        require(origin in setOf("generated", "retrospective")) { "Unknown assessment origin" }
    }
}
