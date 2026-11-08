package com.evanaronson.linguize.menu

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import androidx.core.content.IntentCompat

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

    /** The shared image's content URI, or null when there is none or it isn't safe to open. */
    fun image(intent: Intent, ownPackage: String): Uri? = try {
        intent.takeIf { it.action == Intent.ACTION_SEND }
            ?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_STREAM, Uri::class.java) }
            ?.takeIf { opensSafely(it.scheme, it.authority, ownPackage) }
    } catch (_: BadParcelableException) {
        null
    } catch (_: ClassCastException) {
        null
    }

    /**
     * Only another app's content: never a file path, and never one of this app's own
     * providers. Otherwise any app could ask Linguize to send its own private files (a
     * history export) to the model.
     */
    fun opensSafely(scheme: String?, authority: String?, ownPackage: String): Boolean {
        if (scheme != ContentResolver.SCHEME_CONTENT || authority.isNullOrEmpty()) return false
        // "10@authority" names another user's provider; the authority after it is what matters.
        return authority.split(';').map { it.substringAfterLast('@') }
            .none { it == ownPackage || it.startsWith("$ownPackage.") }
    }
}
