package com.evanaronson.linguize.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import com.evanaronson.linguize.R

fun Context.copyToClipboard(text: String) {
    val clip = ClipData.newPlainText(getString(R.string.app_name), text)
    // The writer's own messages: keep them out of the clipboard preview and keyboard suggestions.
    // Android 12/12L also recognize this flag, before its public constant was added in API 33.
    val sensitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ClipDescription.EXTRA_IS_SENSITIVE
    } else {
        "android.content.extra.IS_SENSITIVE"
    }
    clip.description.extras = PersistableBundle().apply { putBoolean(sensitive, true) }
    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    // Android 13+ shows its own clipboard confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, R.string.clipboard_copied, Toast.LENGTH_SHORT).show()
    }
}
