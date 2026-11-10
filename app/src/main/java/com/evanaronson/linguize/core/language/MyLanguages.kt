package com.evanaronson.linguize.core.language

/**
 * The writer's languages ("my languages"): the ordered ISO 639-1 codes they write in. They are
 * the selector's short list after Linguize, the card's picker, and the languages Settings keeps
 * a writing profile for. Pure rules; `data/language/MyLanguagesStore` keeps the list.
 *
 * The list is never empty: removing the last language does nothing, and a list that can't be
 * read falls back to what the writing profiles name, else Catalan and Spanish.
 */
object MyLanguages {
    private const val SEPARATOR = ","

    /** The most languages the list holds; more would make every picker a long scroll. */
    const val MAX = 24

    /**
     * The list before the writer edits it: the languages their writing profiles name, in the
     * profiles' order (Catalan and Spanish for everyone who used the app before this), else
     * [LanguageScope.DEFAULT_LANGUAGES].
     */
    fun seed(profileLanguages: List<String?>): List<String> =
        clean(profileLanguages).ifEmpty { LanguageScope.DEFAULT_LANGUAGES }

    /** What [stored] holds, or null when nothing usable was stored. Unknown codes are dropped. */
    fun read(stored: String?): List<String>? =
        stored?.split(SEPARATOR)?.let(::clean)?.takeIf { it.isNotEmpty() }

    /** How [languages] are stored. */
    fun write(languages: List<String>): String = clean(languages).joinToString(SEPARATOR)

    /** [languages] with [code] added at the end; unchanged when it's there already, isn't a language, or the list is full. */
    fun add(languages: List<String>, code: String): List<String> {
        val normal = LanguageCodes.normalize(code) ?: return languages
        if (normal in languages || languages.size >= MAX) return languages
        return languages + normal
    }

    /** [languages] without [code]; the last language stays. */
    fun remove(languages: List<String>, code: String): List<String> {
        val left = languages - code
        return if (left.isEmpty()) languages else left
    }

    /** [languages] with [code] moved to [index] (clamped); unchanged when it isn't in the list. */
    fun move(languages: List<String>, code: String, index: Int): List<String> {
        if (code !in languages) return languages
        val rest = languages - code
        return rest.toMutableList().apply { add(index.coerceIn(0, rest.size), code) }
    }

    private fun clean(codes: List<String?>): List<String> =
        codes.mapNotNull(LanguageCodes::normalize).distinct().take(MAX)
}
