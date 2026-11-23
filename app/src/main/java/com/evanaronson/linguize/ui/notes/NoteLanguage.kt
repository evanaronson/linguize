package com.evanaronson.linguize.ui.notes

import androidx.compose.foundation.text.KeyboardOptions
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.ui.components.withLanguage

/**
 * Lab, Language scope: the language Notes checks in, always the app's current language
 * ([appLanguage], set by the one selector at the top), any ISO 639-1 language. Linguize (Auto,
 * null) detects. Notes has no picker of its own any more.
 */
internal fun noteCheckLanguage(appLanguage: String?): Language? = Language.forCode(appLanguage)

/**
 * Lab, Composer language: the editor's keyboard, hinted to the note's language [tag] (from
 * [noteCheckLanguage]); with Linguize (Auto) there is no tag and the keyboard is left as it is.
 */
internal fun noteKeyboardOptions(tag: String?): KeyboardOptions = KeyboardOptions.Default.withLanguage(tag)
