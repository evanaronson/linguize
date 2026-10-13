package com.evanaronson.linguize.data

import android.content.Context

/** Temporary preview diagnostics. Stores two tap traces, never editor contents. */
internal object ShortcutProbe {
    fun preferences(context: Context) = context.getSharedPreferences("shortcut_probe", Context.MODE_PRIVATE)

    fun connected(context: Context) {
        preferences(context).edit().putLong("connected", System.currentTimeMillis()).apply()
    }

    fun begin(context: Context): Int {
        val prefs = preferences(context)
        val tap = prefs.getInt("taps", 0) + 1
        prefs.edit()
            .putString("previous", prefs.getString("latest", ""))
            .putInt("taps", tap)
            .putLong("received", System.currentTimeMillis())
            .putString("latest", "$tap: received")
            .apply()
        return tap
    }

    fun record(context: Context, tap: Int, stages: List<String>) {
        val prefs = preferences(context)
        if (prefs.getInt("taps", 0) != tap) return
        prefs.edit().putString("latest", "$tap: ${stages.takeLast(8).joinToString(" / ")}").apply()
    }
}
