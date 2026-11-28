package com.evanaronson.linguize.core.time

/**
 * Lab, Time spent: a message History shows that has no [TimeRecord]: a check saved before Time
 * spent existed (or any other visible session without one). It counts with its writing
 * estimated ([WritingEstimator], from the writer's speed around [at]) and its review not timed:
 * review is never estimated. Derived from History when read, never stored, so deleting the
 * check removes it.
 */
data class UntimedMessage(
    /** The History session. */
    val sessionId: String,
    /** History's origin token: `chat`, `menu` or `button`; null when unknown. */
    val origin: String?,
    /** ISO 639-1 code of the check's language (asked for, else detected); null when unknown. */
    val language: String?,
    /** Words in the text the writer wrote ([TimeRecord.countWords]). */
    val words: Int,
    /** When it was sent or put back (else when its check started). */
    val at: Long,
) {
    companion object {
        /** [text] is the session's original text, before any change. */
        fun of(sessionId: String, origin: String?, language: String?, text: String, at: Long) =
            UntimedMessage(sessionId, origin, language, TimeRecord.countWords(text), at)
    }
}
