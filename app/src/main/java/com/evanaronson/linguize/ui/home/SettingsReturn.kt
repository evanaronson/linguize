package com.evanaronson.linguize.ui.home

/**
 * One accessibility popup's return from an external activity. The gateway carries the
 * token returned by [await], so a delayed result from an older detour cannot reveal a
 * newer popup. The callback is consumed once and cancelled when its popup closes.
 * Main thread only.
 */
object SettingsReturn {
    private var waiting: (() -> Unit)? = null
    private var token = 0L

    /** Return to the card when leaving the app, even after visiting Home or Recent. */
    fun await(onLeft: () -> Unit): Long {
        waiting = onLeft
        return ++token
    }

    /** [onLeft] no longer waits, if it still did: its card closed first. */
    fun cancel(onLeft: () -> Unit) {
        if (waiting === onLeft) waiting = null
    }

    /** The matching external detour ended: tell its waiting card, if it still exists. */
    fun left(returnToken: Long) {
        if (returnToken != token) return
        val pending = waiting ?: return
        waiting = null
        pending()
    }
}
