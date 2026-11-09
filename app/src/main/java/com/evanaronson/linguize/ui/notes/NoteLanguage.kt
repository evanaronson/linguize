package com.evanaronson.linguize.ui.notes

import com.evanaronson.linguize.core.Language

/**
 * Lab, Language scope: the language Notes checks in, always the app's current language
 * ([appLanguage], set by the one selector at the top). Linguize (Auto, null) detects, and so
 * does a language no check can be pinned to. Notes has no picker of its own any more.
 */
internal fun noteCheckLanguage(appLanguage: String?): Language? = Language.forCode(appLanguage)
