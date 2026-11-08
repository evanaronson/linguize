package com.evanaronson.linguize.core.writingpad

/**
 * When the Writing pad checks: once the writer has paused for [idleMillis], and only when the
 * text differs from what was last checked. At most one check runs; starting a newer one
 * cancels the older, and an answer for a check that is no longer current is ignored.
 * An edit while a check runs doesn't cancel it: its answer still lands on the live text
 * through [LiveSuggestions.remap], and the pause after the edit starts the next check.
 *
 * Pure and clock-free: callers pass the time, so the rules are testable without waiting.
 * [checkable] says whether a text is worth sending at all (not blank, not too long).
 */
class CheckSchedule(
    val idleMillis: Long = DEFAULT_IDLE_MILLIS,
    private val checkable: (String) -> Boolean = { it.isNotBlank() },
) {
    /** One check: [generation] tells a current answer from a stale one. */
    data class Request(val generation: Long, val text: String)

    /** Start [request]; cancel [cancelled] first when it is still running. */
    data class Start(val request: Request, val cancelled: Request?)

    var text: String = ""
        private set
    private var editedAt = Long.MIN_VALUE / 2
    private var generation = 0L

    /** The text the suggestions on screen describe, through a check or an accepted change. */
    var settled: String? = null
        private set

    /** The last text whose check failed; it isn't sent again until the writer edits or retries. */
    var failed: String? = null
        private set

    /** The check running now, if any. */
    var running: Request? = null
        private set

    /** Whether the current text still needs a check: one will start after the pause. */
    val pending: Boolean
        get() = checkable(text) && text != settled && text != failed && running?.text != text

    /** The writer changed the text at [now]. */
    fun edited(text: String, now: Long) {
        if (text == this.text) return
        this.text = text
        editedAt = now
    }

    /** When [due] will next have something to start, or null when nothing is pending. */
    fun wakeAt(): Long? = if (pending) editedAt + idleMillis else null

    /** The check to start at [now], if the writer has paused long enough. */
    fun due(now: Long): Start? {
        if (!pending || now < editedAt + idleMillis) return null
        val cancelled = running
        val request = Request(++generation, text)
        running = request
        return Start(request, cancelled)
    }

    /** Checks the current text now, even right after a failure of the same text. */
    fun retry(now: Long): Start? {
        failed = null
        editedAt = now - idleMillis
        return due(now)
    }

    /** [request] answered. False when it is stale and its answer must be ignored. */
    fun finished(request: Request): Boolean {
        if (running?.generation != request.generation) return false
        running = null
        settled = request.text
        failed = null
        return true
    }

    /** [request] failed. False when it is stale. */
    fun failed(request: Request): Boolean {
        if (running?.generation != request.generation) return false
        running = null
        failed = request.text
        return true
    }

    /**
     * The writer accepted a change, turning [before] into [after]. The other suggestions were
     * moved with it, so when they described [before] they describe [after]: no new check is needed.
     */
    fun accepted(before: String, after: String, now: Long) {
        if (settled == before) settled = after
        edited(after, now)
    }

    /** Stops everything, as when the editor closes; returns the check to cancel, if one runs. */
    fun stop(): Request? {
        val cancelled = running
        running = null
        generation++
        return cancelled
    }

    companion object {
        /** About the pause between sentences: long enough not to check every word. */
        const val DEFAULT_IDLE_MILLIS = 1500L
    }
}
