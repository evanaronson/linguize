package com.evanaronson.linguize.data.language

import android.content.Context
import com.evanaronson.linguize.core.language.LanguageCatalog

/**
 * The app's one [LanguageCatalog], read from `assets/lab_languages.json` the first time it is
 * asked for and kept for the life of the process. The file is about 30 KB; the first read takes
 * a few milliseconds, so a screen may ask for it on the main thread.
 */
object LanguageCatalogAsset {
    const val FILE = "lab_languages.json"

    @Volatile private var loaded: LanguageCatalog? = null

    fun get(context: Context): LanguageCatalog = loaded ?: synchronized(this) {
        loaded ?: LanguageCatalog.parse(
            context.applicationContext.assets.open(FILE).bufferedReader().use { it.readText() },
        ).also { loaded = it }
    }
}
