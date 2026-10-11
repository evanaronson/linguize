package com.evanaronson.linguize.core

import com.evanaronson.linguize.codec.Tokens

/** A language a check can be pinned to; none means the model detects it. */
data class Language(
    /** ISO 639-1 code: what history keeps, and what never changes. */
    val code: String,
    /** The language's English name, as the prompt and the model's answers write it. */
    val name: String,
    /** The variety treated as standard. */
    val variety: String,
) {
    companion object {
        val Catalan = Language("ca", "Catalan", "standard Central Catalan")
        val Spanish = Language("es", "Spanish", "Peninsular")

        /** Every language a check can be pinned to, in the order pickers list them. */
        val all = listOf(Catalan, Spanish)

        /** The language with [code], or null when it isn't one of [all]. */
        fun forCode(code: String?): Language? = all.firstOrNull { it.code == code }

        /**
         * The language [stored] stands for: a code, or the English name history kept before
         * it kept codes. Null when it's neither (or null).
         */
        fun read(stored: String?): Language? = forCode(stored) ?: all.firstOrNull { it.name == stored }
    }
}

/** How strictly fixes cover punctuation and capitalisation; check_prompt.md defines each level. */
enum class Punctuation(
    /** What the prompt sends and settings and history keep. */
    val token: String,
) {
    Strict("strict"),
    Moderate("moderate"),
    Casual("casual"),
    ;

    companion object {
        val tokens = Tokens(entries, Punctuation::token)
    }
}

/** Which judgments a check makes; the model skips the others entirely. */
enum class Judgments(
    /** What the prompt sends and settings and history keep. */
    val token: String,
    val kinds: Set<EditKind>,
) {
    Both("both", setOf(EditKind.Fix, EditKind.Natural)),
    FixOnly("fix", setOf(EditKind.Fix)),
    NaturalizeOnly("naturalize", setOf(EditKind.Natural)),
    ;

    companion object {
        val tokens = Tokens(entries, Judgments::token)
    }
}
