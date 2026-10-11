package com.evanaronson.linguize.ui.home

/**
 * How a card that opened Settings learns that Settings was left (closed, or the user went
 * home or to recents), so it can come back. The card [waits][await] when it opens Settings,
 * [HomeActivity] says when Settings is [left], and the waiting card is told once; after
 * that nothing waits until a card opens Settings again. Visiting Linguize's Home keeps
 * the card waiting until the user returns to it. One card waits at a time: the
 * accessibility overlay shows one card. Main thread only.
 */
object SettingsReturn {
    private var waiting: (() -> Unit)? = null

    /** Return to the card when leaving the app, even after visiting Home or Recent. */
    fun await(onLeft: () -> Unit) {
        waiting = onLeft
    }

    /** [onLeft] no longer waits, if it still did: its card closed first. */
    fun cancel(onLeft: () -> Unit) {
        if (waiting === onLeft) waiting = null
    }

    /** Settings was left: tells the card waiting for it, if any. */
    fun left() {
        val pending = waiting ?: return
        waiting = null
        pending()
    }
}
