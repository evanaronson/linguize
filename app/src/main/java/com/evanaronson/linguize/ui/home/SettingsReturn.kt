package com.evanaronson.linguize.ui.home

/**
 * How a card that opened Settings learns that Settings was left (closed, or the user went
 * home or to recents), so it can come back. The card [waits][await] when it opens Settings,
 * [HomeActivity] says when Settings is [left], and the waiting card is told once; after
 * that nothing waits until a card opens Settings again. Choosing Linguize's Home instead
 * ends the card without restoring it. One card waits at a time: the
 * accessibility overlay shows one card. Main thread only.
 */
object SettingsReturn {
    private class Waiting(val onLeft: () -> Unit, val onHome: () -> Unit)
    private var waiting: Waiting? = null

    /** Return to the card on leaving Settings; end it through [onHome] when entering Home. */
    fun await(onLeft: () -> Unit, onHome: () -> Unit) {
        waiting = Waiting(onLeft, onHome)
    }

    /** [onLeft] no longer waits, if it still did: its card closed first. */
    fun cancel(onLeft: () -> Unit) {
        if (waiting?.onLeft === onLeft) waiting = null
    }

    /** Settings was left: tells the card waiting for it, if any. */
    fun left() {
        val pending = waiting ?: return
        waiting = null
        pending.onLeft()
    }

    /** Home was chosen inside Linguize: end the hidden card instead of restoring it. */
    fun home() {
        val pending = waiting ?: return
        waiting = null
        pending.onHome()
    }
}

