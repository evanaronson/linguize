package com.evanaronson.linguize.data.lab

import android.content.Context

/**
 * Lab, Type to accept: whether accepting a change on the check card means typing it, in its
 * own preferences file (`lab_type_to_accept`), never the app's settings. Off until the
 * writer turns it on. Two switches under it say how a correctly typed change is felt and heard:
 * a light vibration (on by default) and a short sound (off by default).
 */
class TypeToAcceptPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = runCatching { prefs.getBoolean(ENABLED, false) }.getOrDefault(false)
        set(value) = prefs.edit().putBoolean(ENABLED, value).apply()

    /** Vibrate when correct: a light haptic as a typed change matches. */
    var vibrate: Boolean
        get() = runCatching { prefs.getBoolean(VIBRATE, true) }.getOrDefault(true)
        set(value) = prefs.edit().putBoolean(VIBRATE, value).apply()

    /** Sound when correct: a short, quiet sound as a typed change matches. */
    var sound: Boolean
        get() = runCatching { prefs.getBoolean(SOUND, false) }.getOrDefault(false)
        set(value) = prefs.edit().putBoolean(SOUND, value).apply()

    private companion object {
        const val FILE = "lab_type_to_accept"
        const val ENABLED = "enabled"
        const val VIBRATE = "vibrate"
        const val SOUND = "sound"
    }
}
