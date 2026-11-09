package com.evanaronson.linguize.core.language

/**
 * Lab, Language scope: the writer's current language is app-level context, not a per-screen
 * filter. One selector in the top bar of the four tabs sets it: **Linguize** (Auto, the
 * default: every language, detected), **Catalanize** (Catalan) or **Castilianize** (Spanish),
 * then any other language found in the data. Chat, Notes, Progress and History all follow it.
 * Pure: which choices the selector offers, what a stored choice means, and whether a piece of
 * data belongs to the current scope. A null language is Auto everywhere.
 */
object LanguageScope {
    private val CODE = Regex("[a-z]{2}")

    /** What an explicit Auto choice is stored as. Anything that isn't a language code reads as Auto. */
    const val AUTO = "auto"

    /** The brand languages, in the selector's order after Linguize: Catalanize, Castilianize. */
    val BRANDS: List<String> = listOf("ca", "es")

    /** Whether [code] looks like an ISO 639-1 code: what History, the corpus and the chat keep. */
    fun isCode(code: String?): Boolean = code != null && CODE.matches(code)

    /**
     * The current language from what was [stored]: a language code is kept as it is (a switch
     * made before Auto existed included); nothing, [AUTO] or anything else is Auto (null).
     */
    fun current(stored: String?): String? = stored?.takeIf(::isCode)

    /** What to store for [language]: its code, or [AUTO]. */
    fun stored(language: String?): String = language?.takeIf(::isCode) ?: AUTO

    /**
     * What the selector lists, in order: Auto (null), the brand languages, then any other
     * language found in the writer's data (most used first), then [current] if it is none of these.
     */
    fun options(dataLanguages: List<String>, current: String?): List<String?> =
        listOf<String?>(null) + (BRANDS + dataLanguages + listOfNotNull(current)).filter(::isCode).distinct()

    /**
     * Under Auto, the languages that views which are inherently per-language (Practice, Words,
     * conjugations, patterns, phrases) show a block for: the brand languages that have data,
     * then any other language with data, alphabetically. With no data yet, the brand languages,
     * so every view stays one tap away.
     */
    fun inUse(found: Collection<String?>): List<String> {
        val codes = found.filter(::isCode).map { it!! }.toSet()
        if (codes.isEmpty()) return BRANDS
        return BRANDS.filter { it in codes } + (codes - BRANDS.toSet()).sorted()
    }

    /**
     * Whether something written in [language] shows while [scope] is current. Null [scope] is
     * Auto, every language. Data with no known language shows only under Auto: a scoped view
     * never guesses where it belongs.
     */
    fun keeps(language: String?, scope: String?): Boolean = scope == null || language == scope
}
