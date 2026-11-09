package com.evanaronson.linguize.ui.write

import com.evanaronson.linguize.core.Language

/**
 * Lab, Language scope: the language Write checks in. By default the app's current language
 * ([appLanguage]); the card's language picker can change it ([picked]: a language code, or
 * [AUTO] for auto-detect) until the app's language is switched. Null is auto-detect.
 */
internal fun writeCheckLanguage(appLanguage: String?, picked: String?): Language? = when (picked) {
    AUTO -> null
    null -> Language.forCode(appLanguage)
    else -> Language.forCode(picked)
}

/** What the picker's choice is kept as: a language's code, or [AUTO]. */
internal fun pickedToken(language: Language?): String = language?.code ?: AUTO

internal const val AUTO = "auto"
