package com.evanaronson.linguize.data.lab

import android.content.Context

/**
 * Lab, Type to accept: whether accepting a change on the check card means typing it, in its
 * own preferences file (`lab_type_to_accept`), never the app's settings. Off until the
 * writer turns it on.
 */
class TypeToAcceptPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = runCatching { prefs.getBoolean(ENABLED, false) }.getOrDefault(false)
        set(value) = prefs.edit().putBoolean(ENABLED, value).apply()

    private companion object {
        const val FILE = "lab_type_to_accept"
        const val ENABLED = "enabled"
    }
}
