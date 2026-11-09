package com.evanaronson.linguize.data.language

import android.content.Context

/** Where the writer's current language is kept. */
interface LanguageChoice {
    /** The language the writer last switched to (an ISO 639-1 code); null until they first do. */
    var current: String?
}

/**
 * Lab, Language scope: the current language, in its own preferences file
 * (`lab_language_scope`), never the app's settings. Only a choice is stored; until there is
 * one the app works the default out each time.
 */
class LanguageScopePreferences(context: Context) : LanguageChoice {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var current: String?
        get() = runCatching { prefs.getString(CURRENT, null) }.getOrNull()
        set(value) = prefs.edit().putString(CURRENT, value).apply()

    private companion object {
        const val FILE = "lab_language_scope"
        const val CURRENT = "current"
    }
}
