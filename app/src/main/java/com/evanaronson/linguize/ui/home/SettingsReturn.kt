package com.evanaronson.linguize.ui.home

/**
 * How a card that opened Settings learns that Settings was left (closed, or the user went
 * home or to recents), so it can come back. The card [waits][await] when it opens Settings,
 * [HomeActivity] says when Settings is [left], and the waiting card is told once; after
 * that nothing waits until a card opens Settings again. One card waits at a time: the
 * accessibility overlay shows one card. Main thread only.
 */
object SettingsReturn {
    private var waiting: (() -> Unit)? = null

    /** [onLeft] runs the next time Settings is left, instead of whatever waited before. */
    fun await(onLeft: () -> Unit) {
        waiting = onLeft
    }

    /** [onLeft] no longer waits, if it still did: its card closed first. */
    fun cancel(onLeft: () -> Unit) {
        if (waiting === onLeft) waiting = null
    }

    /** Settings was left: tells the card waiting for it, if any. */
    fun left() {
        val onLeft = waiting ?: return
        waiting = null
        onLeft()
    }
}
