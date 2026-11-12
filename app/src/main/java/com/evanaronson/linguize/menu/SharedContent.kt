package com.evanaronson.linguize.menu

import android.content.Intent
import android.os.BadParcelableException

/** What another app shared with Linguize through the share sheet. */
internal object SharedContent {
    /**
     * The shared text, trimmed, or null. Any app can send this intent, so a malformed one
     * is treated as nothing shared rather than a crash.
     */
    fun text(intent: Intent): String? = try {
        intent.takeIf { it.action == Intent.ACTION_SEND }
            ?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: BadParcelableException) {
        null
    } catch (_: ClassCastException) {
        null
    }
}
