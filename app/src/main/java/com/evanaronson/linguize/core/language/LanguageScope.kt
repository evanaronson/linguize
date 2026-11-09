package com.evanaronson.linguize.core.language

/**
 * Lab, Language scope: the writer's current language is app-level context, not a per-screen
 * filter. Chat, Write, Progress and History all start from it. Pure: which languages the
 * switch offers, which one is current, and whether a piece of data belongs to it.
 */
object LanguageScope {
    private val CODE = Regex("[a-z]{2}")

    /** Whether [code] looks like an ISO 639-1 code: what History, the corpus and the chat keep. */
    fun isCode(code: String?): Boolean = code != null && CODE.matches(code)

    /** The current language: the one the writer [chosen], else [fallback] (the chat's start language). */
    fun current(chosen: String?, fallback: String): String = chosen?.takeIf(::isCode) ?: fallback

    /**
     * What the switch lists: the writer's profile languages first (in their order), then any
     * other language found in their data (most used first), then [current] if it is neither.
     */
    fun options(profileLanguages: List<String>, dataLanguages: List<String>, current: String?): List<String> =
        (profileLanguages + dataLanguages + listOfNotNull(current)).filter(::isCode).distinct()

    /**
     * Whether something written in [language] shows while [scope] is current. Null [scope] is
     * "All languages". Data with no known language shows only under All: a scoped view never
     * guesses where it belongs.
     */
    fun keeps(language: String?, scope: String?): Boolean = scope == null || language == scope
}
