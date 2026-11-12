package com.evanaronson.linguize.core.teachme

/**
 * The writer's answers to a lesson's quiz. The first choice for a question counts and locks
 * it, so feedback is immediate and can't be fished for by tapping every option. [reset]
 * starts the quiz again.
 */
data class QuizState(
    val questions: List<QuizQuestion>,
    /** Question index to the chosen option's index. */
    val chosen: Map<Int, Int> = emptyMap(),
) {
    /** The option chosen for [question], or null while it is unanswered. */
    fun choice(question: Int): Int? = chosen[question]

    fun answered(question: Int): Boolean = question in chosen

    /** Null while [question] is unanswered. */
    fun isRight(question: Int): Boolean? =
        chosen[question]?.let { it == questions.getOrNull(question)?.correct }

    /** Chooses [option] for [question]; ignored once it is answered, or when out of range. */
    fun choose(question: Int, option: Int): QuizState {
        val asked = questions.getOrNull(question) ?: return this
        if (answered(question) || option !in asked.options.indices) return this
        return copy(chosen = chosen + (question to option))
    }

    val finished: Boolean get() = questions.isNotEmpty() && questions.indices.all(::answered)

    val score: Int get() = questions.indices.count { isRight(it) == true }

    fun reset(): QuizState = copy(chosen = emptyMap())
}
