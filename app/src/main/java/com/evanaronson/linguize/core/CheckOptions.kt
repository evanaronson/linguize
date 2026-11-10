package com.evanaronson.linguize.core

import com.evanaronson.linguize.codec.Tokens
import com.evanaronson.linguize.core.language.LanguageCodes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A language a check can be pinned to; none means the model detects it. Any ISO 639-1 language
 * is one ([forCode]): the app is a general language application, and Catalan and Spanish are
 * only the two it started with (docs/lab/language-generic.md).
 */
data class Language(
    /** ISO 639-1 code: what history keeps, and what never changes. */
    val code: String,
    /** The language's English name, as the prompt and the model's answers write it. */
    val name: String,
    /** The variety treated as standard when no writing profile names one; null for none. */
    val variety: String? = null,
) {
    companion object {
        val Catalan = Language("ca", "Catalan", "standard Central Catalan")
        val Spanish = Language("es", "Spanish", "Peninsular")

        /**
         * The two languages the app shipped with, in their old picker order. Only for what
         * predates other languages: History's migration of English names to codes, and the
         * writer's languages before they choose any. Pickers list the writer's languages.
         */
        val all = listOf(Catalan, Spanish)

        /** The language with [code]: any ISO 639-1 language. Null when [code] isn't one. */
        fun forCode(code: String?): Language? {
            val normal = LanguageCodes.normalize(code) ?: return null
            return all.firstOrNull { it.code == normal } ?: Language(normal, LanguageCodes.englishName(normal))
        }

        /**
         * The language [stored] stands for: a code, or the English name history kept before
         * it kept codes (only Catalan and Spanish were ever kept by name). Null when it's
         * neither (or null).
         */
        fun read(stored: String?): Language? = forCode(stored) ?: all.firstOrNull { it.name == stored }
    }
}

/** How strictly fixes cover punctuation and capitalisation; check_prompt.md defines each level. */
@Serializable
enum class Punctuation(
    /** What the prompt sends and settings and history keep. */
    val token: String,
) {
    @SerialName("strict") Strict("strict"),
    @SerialName("moderate") Moderate("moderate"),
    @SerialName("casual") Casual("casual"),
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
