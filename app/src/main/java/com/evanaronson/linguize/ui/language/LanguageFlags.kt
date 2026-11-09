package com.evanaronson.linguize.ui.language

import androidx.annotation.DrawableRes
import com.evanaronson.linguize.R

/**
 * Lab, Language scope: the small flag the language switch shows for a language. The one place
 * that maps languages to flags, so it can be swapped for code badges. Catalan is the senyera;
 * Spanish follows the variety in the writer's profile (Peninsular is Spain, Mexican is
 * Mexico, …), Spain when it names none we draw. A language without a flag gets a code badge.
 * A flag is only a picture: what screen readers hear is the language's name, never a country.
 */
@DrawableRes
fun flagFor(code: String, variety: String?): Int? = when (code) {
    "ca" -> R.drawable.lab_language_scope_flag_ca
    "es" -> spanishFlag(variety)
    else -> null
}

@DrawableRes
private fun spanishFlag(variety: String?): Int {
    val named = variety.orEmpty().lowercase()
    return when {
        "mexic" in named || "méxic" in named -> R.drawable.lab_language_scope_flag_mx
        "argentin" in named || "rioplat" in named -> R.drawable.lab_language_scope_flag_ar
        "colombia" in named -> R.drawable.lab_language_scope_flag_co
        else -> R.drawable.lab_language_scope_flag_es
    }
}
