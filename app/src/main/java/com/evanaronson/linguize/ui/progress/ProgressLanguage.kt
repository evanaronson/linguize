package com.evanaronson.linguize.ui.progress

import com.evanaronson.linguize.core.Language

/** The languages Progress switches between: those the per-language views all cover. */
internal val PROGRESS_LANGUAGES: List<String> = Language.all.map { it.code }

/**
 * The language Progress shows: the one [chosen] on the switch, else the one with the most
 * words typed ([wordsByLanguage], most used first, as Your words orders it), else [fallback]
 * (Your conjugations' choice, from the text it counted).
 */
internal fun progressLanguage(chosen: String?, wordsByLanguage: List<Pair<String?, Int>>, fallback: String): String =
    chosen?.takeIf { it in PROGRESS_LANGUAGES }
        ?: wordsByLanguage.firstOrNull { (language, count) -> language in PROGRESS_LANGUAGES && count > 0 }?.first
        ?: fallback.takeIf { it in PROGRESS_LANGUAGES }
        ?: PROGRESS_LANGUAGES.first()
