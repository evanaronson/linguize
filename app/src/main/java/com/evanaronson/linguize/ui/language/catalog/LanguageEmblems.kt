package com.evanaronson.linguize.ui.language.catalog

import androidx.annotation.DrawableRes
import com.evanaronson.linguize.R

// Lab, lang-selector: the circular emblems Linguize draws for the languages no flag fits.
// Keys are the catalog's `emblem` values (lab_languages.json); docs/lab/language-catalog.md says why each.

/** The emblem drawn for a catalog emblem key, or null for a key with no drawable. */
@DrawableRes
fun emblemDrawable(key: String): Int? = when (key) {
    "ae" -> R.drawable.lab_emblem_ae
    "cr" -> R.drawable.lab_emblem_cr
    "cu" -> R.drawable.lab_emblem_cu
    "ff" -> R.drawable.lab_emblem_ff
    "inuit" -> R.drawable.lab_emblem_inuit
    "nv" -> R.drawable.lab_emblem_nv
    "oj" -> R.drawable.lab_emblem_oj
    "os" -> R.drawable.lab_emblem_os
    "pi" -> R.drawable.lab_emblem_pi
    else -> null
}

/** Every key [emblemDrawable] knows. */
val emblemKeys: Set<String> = setOf("ae", "cr", "cu", "ff", "inuit", "nv", "oj", "os", "pi")
