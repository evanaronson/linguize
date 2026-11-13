package com.evanaronson.linguize.core.time

/**
 * Lab, Time spent: counts active time, invisibly. Pure; [clock] is injectable.
 *
 * Time is counted between marks: [start], each [touch] (a keystroke, a change on the card),
 * [resume], and coming back on screen ([visible]). Each gap counts at most [idleCapMs], so a
 * draft or a card left open doesn't keep counting. Nothing counts while [pause]d (the host's
 * own reason, such as waiting for a check) or while hidden (the app or popup isn't on screen).
 * Pausing or hiding counts the gap up to that moment, capped too.
 */
class ActiveTimer(
    private val idleCapMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var activeMs = 0L
    private var lastMark = 0L
    private var paused = false
    private var onScreen = true

    /** Whether [start] (or [touch]) was called since the last [reset] or [stop]. */
    var started = false
        private set

    /** Counting right now: started, not paused, on screen. */
    val running: Boolean get() = started && !paused && onScreen

    /** Starts counting from now, if not started already. */
    fun start() {
        if (started) return
        started = true
        paused = false
        lastMark = clock()
    }

    /** Activity: counts the gap since the last mark (capped). Starts the timer if it hasn't. */
    fun touch() {
        if (!started) return start()
        mark()
    }

    /** Stops counting until [resume], for the host's own reason. */
    fun pause() {
        mark()
        paused = true
    }

    /** Counts again from now, after [pause]. */
    fun resume() {
        if (!started || !paused) return
        paused = false
        lastMark = clock()
    }

    /** The host is on screen, or not (the app went to the background, the screen went off). */
    fun visible(visible: Boolean) {
        if (visible == onScreen) return
        if (!visible) mark()
        onScreen = visible
        if (visible) lastMark = clock()
    }

    /** Active time so far, including the open gap (capped). */
    fun elapsed(): Long = activeMs + if (running) gap() else 0L

    /** Ends it: returns the active time and resets. Visibility is kept. */
    fun stop(): Long = elapsed().also { reset() }

    fun reset() {
        activeMs = 0L
        started = false
        paused = false
        lastMark = 0L
    }

    private fun mark() {
        if (!running) return
        activeMs += gap()
        lastMark = clock()
    }

    private fun gap(): Long = (clock() - lastMark).coerceIn(0L, idleCapMs)

    companion object {
        /** A pause between keystrokes longer than this counts as this long (60 s before rules version 2). */
        const val WRITING_IDLE_CAP_MS = 30_000L

        /** A stretch on the card with nothing changing longer than this counts as this long. */
        const val REVIEW_IDLE_CAP_MS = 120_000L
    }
}
