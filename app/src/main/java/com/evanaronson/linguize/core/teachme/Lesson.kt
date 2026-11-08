package com.evanaronson.linguize.core.teachme

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One decision from a check that the writer asked to learn: [from] became [to] because of
 * [why]. [language] is the English name of the language they wrote in ("Catalan"), or null
 * when the card doesn't know it and the model should tell from the words. [sentence] is the
 * sentence the change sits in, when there is one, so the lesson can speak to this case.
 */
data class LessonTopic(
    val from: String,
    val to: String,
    val why: String,
    val language: String? = null,
    val sentence: String? = null,
) {
    /** A lesson needs something to teach: a reason, and a change that changes something. */
    val teachable: Boolean
        get() = why.isNotBlank() && from.trim() != to.trim() && (from.isNotBlank() || to.isNotBlank())
}

/**
 * A one-screen mini lesson in the writer's own language: the [rule], why their version was
 * wrong or less natural here ([yours]), three [examples] in the target language with
 * translations, the common [trap], and a two-question [quiz].
 */
@Serializable
data class Lesson(
    /** The target language's English name, as the model judged it. */
    val language: String,
    val rule: String,
    @SerialName("your_version") val yours: String,
    val examples: List<LessonExample>,
    val trap: String,
    val quiz: List<QuizQuestion>,
) {
    companion object {
        const val EXAMPLES = 3
        const val QUESTIONS = 2
    }
}

/** A short sentence in the target language and its translation into the writer's language. */
@Serializable
data class LessonExample(val sentence: String, val translation: String)

/** Multiple choice: [correct] is the index into [options]; [feedback] explains the answer. */
@Serializable
data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val correct: Int,
    val feedback: String,
)
