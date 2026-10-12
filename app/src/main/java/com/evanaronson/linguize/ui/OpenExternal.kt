package com.evanaronson.linguize.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.evanaronson.linguize.R

/** Opens system setup, a provider's website or the share sheet from the visible activity. */
internal fun Context.openExternal(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.open_external_failed, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(this, R.string.open_external_failed, Toast.LENGTH_SHORT).show()
    }
}
