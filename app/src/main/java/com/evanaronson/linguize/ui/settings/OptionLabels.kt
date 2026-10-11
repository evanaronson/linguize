package com.evanaronson.linguize.ui.settings

import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation

val Punctuation.label: String
    get() = when (this) {
        Punctuation.Strict -> "Strict"
        Punctuation.Moderate -> "Moderate"
        Punctuation.Casual -> "Casual"
    }

val Punctuation.description: String
    get() = when (this) {
        Punctuation.Strict -> "Flags every missing mark, including final periods, and ¿ and ¡ in Spanish."
        Punctuation.Moderate -> "Flags missing sentence breaks and question marks. Final periods and ¿ are optional."
        Punctuation.Casual -> "Flags missing punctuation only when it would confuse a reader."
    }

val Judgments.label: String
    get() = when (this) {
        Judgments.Both -> "Fixes and rewordings"
        Judgments.FixOnly -> "Fixes only"
        Judgments.NaturalizeOnly -> "Rewordings only"
    }
