package com.evanaronson.linguize.menu

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Selection
import com.evanaronson.linguize.ui.ClipboardText
import com.evanaronson.linguize.ui.quicktile.ClipboardJob

/** What the clipboard held when the tile or a shortcut opened the card. */
sealed interface Copied {
    /** Copied text, with its surrounding whitespace, which stays where it was. */
    data class Text(val selection: Selection) : Copied

    /** Nothing copied, or only blank text. */
    data object Empty : Copied

    /** Something copied that isn't text, like an image or a link to a file. */
    data object NotText : Copied

    /** Android didn't let Linguize read it, typically because its window had no focus yet. */
    data object Unavailable : Copied

    /** The same answer in the form the Understand card takes. */
    val forUnderstand: ClipboardText
        get() = when (this) {
            is Text -> ClipboardText.Text(selection.text)
            Empty, NotText -> ClipboardText.NoText
            Unavailable -> ClipboardText.Unavailable
        }

    companion object {
        /**
         * Reads the first item of [clip]. Only real text counts: a URI or intent is never
         * resolved, so copying a file or link to private content can't leak it into a check.
         * Styled and HTML text keep only their characters.
         */
        fun of(clip: ClipData?): Copied {
            if (clip == null || clip.itemCount == 0) return Empty
            val item = clip.getItemAt(0)
            val text = item.text?.toString()
            if (text == null) {
                return if (item.uri != null || item.intent != null || !clip.description.hasText()) NotText else Empty
            }
            val selection = Selection.of(text)
            return if (selection.text.isEmpty()) Empty else Text(selection)
        }

        private fun ClipDescription.hasText(): Boolean =
            hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
    }
}

/** What the host shows once the clipboard has been read. */
sealed interface ClipboardStep {
    /** Waiting for window focus, which Android 10+ requires before reading. */
    data object Reading : ClipboardStep
    data class Message(val copied: Copied) : ClipboardStep
    data class Choose(val selection: Selection) : ClipboardStep
    data class Check(val selection: Selection) : ClipboardStep
    data class Understand(val selection: Selection) : ClipboardStep

    companion object {
        /** Routes [copied] to the card [job] asked for; anything but text shows why not. */
        fun after(job: ClipboardJob, copied: Copied): ClipboardStep = when (copied) {
            is Copied.Text -> when (job) {
                ClipboardJob.Check -> Check(copied.selection)
                ClipboardJob.Understand -> Understand(copied.selection)
                ClipboardJob.Choose -> Choose(copied.selection)
            }
            else -> Message(copied)
        }
    }
}

/** Caller must own the focused window; called once per explicit request, never observed. */
internal fun Context.readClipboard(): Copied = try {
    Copied.of(getSystemService(ClipboardManager::class.java)?.primaryClip)
} catch (_: RuntimeException) {
    // SecurityException without focus; some OEM clipboards throw their own.
    Copied.Unavailable
}

/**
 * There's no field to put accepted changes back into, so they go back on the clipboard,
 * replacing the text that was copied, with a confirmation that says so. Kept out of the
 * clipboard preview and keyboard suggestions, as the card's Copy does. Returns whether
 * Android took the text.
 */
internal fun Context.putBackOnClipboard(text: String): Boolean {
    val clip = ClipData.newPlainText(getString(R.string.app_name), text)
    val sensitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ClipDescription.EXTRA_IS_SENSITIVE
    } else {
        "android.content.extra.IS_SENSITIVE"
    }
    clip.description.extras = PersistableBundle().apply { putBoolean(sensitive, true) }
    val copied = try {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        true
    } catch (_: RuntimeException) {
        false
    }
    // Always confirm in words: Android's own "Copied" doesn't say the changes are in it.
    Toast.makeText(
        this,
        if (copied) R.string.lab_quick_tile_copied_with_changes else R.string.lab_quick_tile_copy_failed,
        Toast.LENGTH_LONG,
    ).show()
    return copied
}
