package com.evanaronson.linguize.ui.notes

import com.evanaronson.linguize.core.Language

/**
 * Lab, Language scope: the language Notes checks in, always the app's current language
 * ([appLanguage], set by the one selector at the top), any ISO 639-1 language. Linguize (Auto,
 * null) detects. Notes has no picker of its own any more.
 */
internal fun noteCheckLanguage(appLanguage: String?): Language? = Language.forCode(appLanguage)
