package com.evanaronson.linguize.core.time

/**
 * Lab, Time spent: the writing time of one chat draft, invisibly. Pure; [clock] is injectable.
 *
 * Writing starts at the first keystroke that leaves text in the composer and ends at Send.
 * Each keystroke is activity for the idle cap ([ActiveTimer.WRITING_IDLE_CAP_MS]), and nothing
 * counts while the chat is off screen. While the card is open, writing is paused; Keep editing
 * resumes it, and the review time of that card is kept for the same message. A draft that is
 * never sent records nothing.
 */
class DraftTimer(clock: () -> Long = System::currentTimeMillis) {
    private val writing = ActiveTimer(ActiveTimer.WRITING_IDLE_CAP_MS, clock)
    private var earlierReviewMs = 0L

    /** The composer's text changed to [text]. */
    fun typed(text: String) {
        if (writing.started || text.isNotBlank()) writing.touch()
    }

    /** Send tapped: the draft goes to the check. */
    fun sendTapped() = writing.pause()

    /** Keep editing: back to the draft, after [reviewMs] on the card. */
    fun keptEditing(reviewMs: Long) {
        earlierReviewMs += reviewMs.coerceAtLeast(0L)
        writing.resume()
    }

    fun visible(visible: Boolean) = writing.visible(visible)

    /** Writing time so far. */
    fun writingMs(): Long = writing.elapsed()

    /** The message [messageId] goes: what was measured for it. Starts afresh for the next draft. */
    fun sent(messageId: String? = null): DraftTiming =
        DraftTiming(writing.stop(), earlierReviewMs, messageId).also { earlierReviewMs = 0L }

    /** The draft was dropped (another conversation opened). */
    fun reset() {
        writing.reset()
        earlierReviewMs = 0L
    }
}
