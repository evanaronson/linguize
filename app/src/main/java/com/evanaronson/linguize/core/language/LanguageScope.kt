package com.evanaronson.linguize.core.language

/**
 * Lab, Language scope: the writer's current language is app-level context, not a per-screen
 * filter. One selector in the top bar of the four tabs sets it: **Linguize** (Auto, the
 * default: every language, detected), then the writer's languages (`MyLanguages`, any ISO
 * 639-1 code). Chat, Notes, Progress and History all follow it.
 * Pure: which choices the selector offers, what a stored choice means, and whether a piece of
 * data belongs to the current scope. A null language is Auto everywhere.
 */
object LanguageScope {
    private val CODE = Regex("[a-z]{2}")

    /** What an explicit Auto choice is stored as. Anything that isn't a language code reads as Auto. */
    const val AUTO = "auto"

    /**
     * The writer's languages before they choose any: the two the app started with,
     * Catalanize then Castilianize. Only a default; any language can be added or removed.
     */
    val DEFAULT_LANGUAGES: List<String> = listOf("ca", "es")

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
     * What the selector lists, in order: Auto (null), the writer's languages ([mine], in their
     * order), then [current] if it is none of these (a language chosen before it was added).
     */
    fun options(mine: List<String>, current: String?): List<String?> =
        listOf<String?>(null) + (mine + listOfNotNull(current)).filter(::isCode).distinct()

    /**
     * Languages [found] in the writer's data that aren't among [mine], most used first as
     * found: what an "Add languages" screen can suggest first.
     */
    fun suggested(found: List<String?>, mine: List<String>): List<String> =
        found.filter(::isCode).map { it!! }.distinct() - mine.toSet()

    /**
     * Under Auto, the languages that views which are inherently per-language (Practice, Words,
     * conjugations, patterns, phrases) show a block for: the writer's languages ([mine]) that
     * have data, then any other language with data, alphabetically. With no data yet, the
     * writer's languages, so every view stays one tap away.
     */
    fun inUse(found: Collection<String?>, mine: List<String> = DEFAULT_LANGUAGES): List<String> {
        val codes = found.filter(::isCode).map { it!! }.toSet()
        val ordered = mine.filter(::isCode).distinct()
        if (codes.isEmpty()) return ordered
        return ordered.filter { it in codes } + (codes - ordered.toSet()).sorted()
    }

    /**
     * Whether something written in [language] shows while [scope] is current. Null [scope] is
     * Auto, every language. Data with no known language shows only under Auto: a scoped view
     * never guesses where it belongs.
     */
    fun keeps(language: String?, scope: String?): Boolean = scope == null || language == scope
}
