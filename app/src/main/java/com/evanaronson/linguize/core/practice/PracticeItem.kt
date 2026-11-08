package com.evanaronson.linguize.core.practice

import com.evanaronson.linguize.core.SuggestionAssessment

/**
 * One drill made from a fix the writer applied: the sentence as they wrote it, with only
 * this mistake left in (their other applied fixes in the sentence are already made), and
 * the same sentence corrected. [id] is the source suggestion's id, so scheduling state
 * survives rebuilding the deck and disappears with the History entry it came from.
 */
data class PracticeItem(
    val id: String,
    val sessionId: String,
    /** When the source check started. */
    val checkedAt: Long,
    /** ISO 639-1 code of the language the fix was made in; null when unknown. */
    val language: String?,
    /** The sentence with the mistake. */
    val sentence: String,
    /** The mistaken words in [sentence], widened to whole words: what the writer replaces. */
    val wrongStart: Int,
    val wrongEnd: Int,
    /** What replaces [wrong] in [corrected]. */
    val right: String,
    /** The recorded reason, in the writer's own language; null when none was saved. */
    val why: String? = null,
    val assessment: SuggestionAssessment? = null,
    /** Where the source check came from: History's origin token ("chat" for Lab Chat), null when unknown. */
    val origin: String? = null,
) {
    val wrong: String get() = sentence.substring(wrongStart, wrongEnd)

    /** [sentence] with the fix made. */
    val corrected: String get() = sentence.substring(0, wrongStart) + right + sentence.substring(wrongEnd)

    /** Whether the writer types the fix or picks between the two sentences. */
    val mode: Mode get() = modeOf(wrong, right)

    enum class Mode { Type, Choose }

    companion object {
        /** A replacement longer than this is picked, not typed. */
        const val MAX_TYPED_WORDS = 4
        const val MAX_TYPED_LENGTH = 40

        /**
         * Typing suits a word or a short phrase. Picking suits what is hard to type or to see:
         * a deletion, a change of punctuation, spacing or capitals alone, and long rewrites.
         */
        fun modeOf(wrong: String, right: String): Mode = when {
            right.isBlank() -> Mode.Choose
            Answers.lettersOnly(wrong).equals(Answers.lettersOnly(right), ignoreCase = true) -> Mode.Choose
            right.length > MAX_TYPED_LENGTH -> Mode.Choose
            Answers.words(right).size > MAX_TYPED_WORDS -> Mode.Choose
            else -> Mode.Type
        }
    }
}
