package com.evanaronson.linguize.ui.card

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.R

/**
 * Lab, Consistency: where the card can send someone whose text is in another language than
 * the one it was checked as. The card is the same everywhere; only this hint differs.
 */
enum class LanguageHint {
    /** A popup over another app: the card's own language picker (Auto Detect). */
    Picker,

    /** Inside the app (Notes): the one language selector at the top (Linguize). */
    AppSelector,

    /** A conversation: its language is fixed, so there is nothing to choose. */
    Fixed,
}

/** The hint the card under this point gives. Popups keep the default, their own picker. */
val LocalLanguageHint = staticCompositionLocalOf { LanguageHint.Picker }

/** The line under "This isn't Catalan": what it looks like ([found], a language's name) and what to do. */
@Composable
internal fun wrongLanguageHint(hint: LanguageHint, found: String?): String = when (hint) {
    LanguageHint.Picker -> found?.let { stringResource(R.string.wrong_language_detected, it) }
        ?: stringResource(R.string.choose_auto_language)
    LanguageHint.AppSelector -> found?.let { stringResource(R.string.lab_consistency_wrong_language_app, it) }
        ?: stringResource(R.string.lab_consistency_choose_auto_app)
    LanguageHint.Fixed -> found?.let { stringResource(R.string.lab_consistency_wrong_language_chat, it) }
        ?: stringResource(R.string.lab_consistency_conversation_language)
}
