package com.evanaronson.linguize.ui.time

import androidx.compose.runtime.MutableState
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.time.ActiveTimer
import com.evanaronson.linguize.core.time.DraftTiming
import com.evanaronson.linguize.core.time.TimeLog
import com.evanaronson.linguize.core.time.TimeRecord

/**
 * Lab, Time spent: the review time of one check card, kept by its
 * [com.evanaronson.linguize.ui.card.CheckViewModel]. Invisible: nothing is shown while timing.
 *
 * It counts while a result is on the card (any answer, including a question to clarify), and
 * not while the card waits for the model or shows a failure. Every change of what the card
 * shows (accepting, undoing, answering) is activity for the idle cap. Hosts say when the card is
 * off screen ([visible]). When the card closes and History saves its session, one
 * [TimeRecord] goes to [log]; a card History doesn't save (Try it, History off, nothing put back)
 * records nothing.
 */
class CardTiming(
    private val log: TimeLog = TimeLog.None,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val review = ActiveTimer(ActiveTimer.REVIEW_IDLE_CAP_MS, clock)

    /** What the card shows now: [result] is true while it shows an answer. */
    fun showing(result: Boolean) {
        when {
            !result -> if (review.started) review.pause()
            review.started -> { review.resume(); review.touch() }
            else -> review.start()
        }
    }

    /** The card's host is on screen, or isn't (the app opened over it, the screen went off). */
    fun visible(visible: Boolean) = review.visible(visible)

    /** Review time so far. */
    fun reviewMs(): Long = review.elapsed()

    /** The card closed without saving anything. */
    fun reset() = review.reset()

    /**
     * The card closed and History is saving it as [sessionId] (null: nothing saved, nothing
     * recorded). [text] is what the writer wrote; [revision] the card's last review, if any.
     */
    fun closed(sessionId: String?, origin: String, language: String?, text: String, revision: Revision?, draft: DraftTiming? = null) {
        val reviewMs = review.stop() + (draft?.earlierReviewMs ?: 0L)
        sessionId ?: return
        // Timing never gets in the way of closing a card.
        runCatching {
            log.record(
                TimeRecord(
                    sessionId = sessionId,
                    origin = origin,
                    language = language,
                    writingMs = draft?.writingMs,
                    reviewMs = reviewMs,
                    words = TimeRecord.countWords(text),
                    changesShown = revision?.edits?.size ?: 0,
                    changesAccepted = revision?.acceptedCount ?: 0,
                    at = clock(),
                    messageId = draft?.messageId,
                ),
            )
        }
    }
}

/** A [MutableState] that also tells [onSet] each value written to it: how the card's state reaches [CardTiming]. */
class ObservedState<T>(private val inner: MutableState<T>, private val onSet: (T) -> Unit) : MutableState<T> by inner {
    override var value: T
        get() = inner.value
        set(value) {
            inner.value = value
            onSet(value)
        }
}
