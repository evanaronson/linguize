package com.evanaronson.linguize.ui.language

import com.evanaronson.linguize.core.language.LanguageCatalog
import com.evanaronson.linguize.core.language.MyLanguages

/**
 * Lab, lang-selector: what the Languages page lists. Pure, so the sections and the rules for
 * checking and unchecking are tested without a screen.
 */
object AddLanguages {
    /** A group of rows on the page. */
    enum class Kind {
        /** The writer's languages, in the selector's order (each can move up). */
        Yours,

        /** Languages found in History that the writer hasn't added, most used first. */
        Suggested,

        /** Every catalog language, A to Z by English name. */
        Every,

        /** What the search found, best match first. */
        Results,
    }

    /** One row: a language code, and whether it is one of the writer's (checked). */
    data class Row(val code: String, val checked: Boolean)

    data class Section(val kind: Kind, val rows: List<Row>)

    /**
     * The page's sections. With a blank [query]: Your languages, then Suggested (when there are
     * any), then Every language. With a query: only the search's results, which match the
     * English name, the language's own name, the brand name, an alias or the code.
     */
    fun sections(catalog: LanguageCatalog, mine: List<String>, suggested: List<String>, query: String): List<Section> {
        val yours = mine.toSet()
        fun rows(codes: List<String>) = codes.map { Row(it, it in yours) }
        if (query.isNotBlank()) return listOf(Section(Kind.Results, rows(catalog.search(query).map { it.code })))
        val suggestions = suggested.filter { it !in yours && catalog.find(it) != null }.distinct()
        return listOfNotNull(
            Section(Kind.Yours, rows(mine)),
            suggestions.takeIf { it.isNotEmpty() }?.let { Section(Kind.Suggested, rows(it)) },
            Section(Kind.Every, rows(catalog.all().map { it.code })),
        )
    }

    /** What checking or unchecking [code] does to the writer's languages. */
    enum class Toggle { Add, Remove, KeepLast, Full }

    /**
     * Unchecking removes the language from the list (its data stays); the last language can't be
     * unchecked ([Toggle.KeepLast]), and a full list ([MyLanguages.MAX]) takes no more ([Toggle.Full]).
     */
    fun toggle(mine: List<String>, code: String): Toggle = when {
        code in mine -> if (mine.size <= 1) Toggle.KeepLast else Toggle.Remove
        mine.size >= MyLanguages.MAX -> Toggle.Full
        else -> Toggle.Add
    }
}
