package com.evanaronson.linguize.core.time

import com.evanaronson.linguize.core.words.WordTokenizer

/**
 * Lab, Time spent: what was measured for one committed message (sent from the chat, or put
 * back in another app), keyed by its History session. Only measurements are kept; the writing
 * time of a message written elsewhere is estimated when read ([WritingEstimator]), so estimates
 * improve as the writer's measured speed does.
 */
data class TimeRecord(
    /** The History session the message was saved as. */
    val sessionId: String,
    /** History's origin token: `chat`, `menu` or `button`. */
    val origin: String,
    /** ISO 639-1 code of the check's language (asked for, else detected); null when unknown. */
    val language: String?,
    /** Measured writing time (chat only): first keystroke to Send, active time. Null elsewhere. */
    val writingMs: Long?,
    /** Measured review time: active time with a result on the card, until it was sent or put back. */
    val reviewMs: Long,
    /** Words in the text the writer wrote ([countWords]). */
    val words: Int,
    /** Changes the card offered (fixes and rewordings) in its last result. */
    val changesShown: Int,
    /** Of those, the ones accepted and in the text that went. */
    val changesAccepted: Int,
    /** When it was sent or put back. */
    val at: Long,
    /** The measurement rules this was recorded with ([RULES_VERSION]). */
    val rules: Int = RULES_VERSION,
    /** The chat message, when it was one. */
    val messageId: String? = null,
) {
    val measuredWriting: Boolean get() = writingMs != null

    companion object {
        /** Version 1: the idle caps in [ActiveTimer], review only while a result shows. */
        const val RULES_VERSION = 1

        /** Words as Your words splits them (names included: they were typed too). */
        fun countWords(text: String): Int = WordTokenizer.tokenize(text).size
    }
}

/** Where measurements go. The app's is a Lab-local database; [None] keeps nothing. */
fun interface TimeLog {
    fun record(record: TimeRecord)

    companion object {
        val None = TimeLog { }
    }
}

/**
 * What the chat measured before Send, handed to the card that saves the message: writing time,
 * and review time from earlier openings of the card for the same draft (before Keep editing).
 */
data class DraftTiming(val writingMs: Long, val earlierReviewMs: Long = 0L, val messageId: String? = null)
