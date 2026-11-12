package com.evanaronson.linguize.core.language

import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One language of the app-wide catalog (`assets/lab_languages.json`): an ISO 639-1 [code], its
 * [english] name and [native] name (autonym, both from Unicode CLDR where it has them), the
 * product's [brand] verb ("Catalanize", "Anglicize") and its circular [flag] key, or, when no
 * flag is fair, the [emblem] Linguize draws for it instead. See docs/lab/language-catalog.md.
 */
@Serializable
data class CatalogLanguage(
    val code: String,
    val english: String,
    val native: String,
    val brand: String,
    /** A circle-flags file name ("es-ct" is the senyera, "gb" the UK), not a language code. */
    val flag: String? = null,
    /**
     * When there is no [flag]: the key of the circular emblem Linguize draws for the language
     * (the Navajo Nation's flag, the Inuit flag, a letter of its script). Never a code badge.
     */
    val emblem: String? = null,
    /** "living", "classical" (Latin, Sanskrit, …) or "constructed" (Esperanto, …). */
    val kind: String = LIVING,
    /** True for an ISO 639-3 macrolanguage (Arabic, Chinese, Norwegian, …). */
    val macrolanguage: Boolean = false,
    /** The macrolanguage this one belongs to, when that is in the catalog too (nb, nn: "no"). */
    val macro: String? = null,
    /** Other English names search should find ("Farsi", "Castilian", "Flemish"). */
    val aliases: List<String> = emptyList(),
    /** Regional varieties with their own flag (Spanish: Mexico, Argentina, Colombia, …). */
    val varieties: List<CatalogVariety> = emptyList(),
    /** Why the brand or flag is what it is, where that isn't the plain rule. */
    val notes: String? = null,
) {
    companion object {
        const val LIVING = "living"
        const val CLASSICAL = "classical"
        const val CONSTRUCTED = "constructed"
    }
}

/** A variety of a language: its [name], words that pick it out of a profile's variety ([match]) and its [flag] key. */
@Serializable
data class CatalogVariety(val name: String, val match: List<String>, val flag: String)

@Serializable
private class CatalogFile(val version: Int, val languages: List<CatalogLanguage>)

/**
 * The canonical list of languages the app can name: ISO 639-1, with English and native names,
 * brand verbs and flag keys. Pure: build it with [parse] from the asset's text (the app loads it
 * once, `data/language/LanguageCatalogAsset`). Codes are matched case-insensitively and a BCP 47
 * tag finds its language ("pt-BR" is Portuguese); retired codes ("iw", "in") find today's.
 */
class LanguageCatalog(languages: List<CatalogLanguage>) {
    private val sorted: List<CatalogLanguage> = run {
        val collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }
        languages.sortedWith { a, b -> collator.compare(a.english, b.english) }
    }
    private val byCode: Map<String, CatalogLanguage> = sorted.associateBy { it.code }
    private val keys: List<Pair<CatalogLanguage, List<String>>> = sorted.map { language ->
        language to (listOf(language.english, language.native, language.brand) + language.aliases).map(::fold)
    }

    /** Every language, sorted by English name. */
    fun all(): List<CatalogLanguage> = sorted

    /** The language for an ISO 639-1 code or a tag starting with one ("ca", "CA", "pt-BR", "iw"); null if unknown. */
    fun find(code: String?): CatalogLanguage? {
        val base = code?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase(Locale.ROOT) ?: return null
        return byCode[base] ?: RETIRED[base]?.let(byCode::get)
    }

    /** The brand verb for [code] ("Catalanize", "Anglicize"), or null for a code not in the catalog. */
    fun brandName(code: String?): String? = find(code)?.brand

    /** The emblem key for a language without a flag ("nv", "inuit"); null when it has a flag or isn't known. */
    fun emblemKey(code: String?): String? = find(code)?.takeIf { it.flag == null }?.emblem

    /**
     * The flag key for [code]: the [variety]'s own flag when a variety's words appear in it
     * (Spanish "Mexican" is "mx"), else the language's flag; null when the language has none
     * (draw a code badge).
     */
    fun flagKey(code: String?, variety: String? = null): String? {
        val language = find(code) ?: return null
        val named = variety?.let(::fold).orEmpty()
        if (named.isNotEmpty()) {
            language.varieties.firstOrNull { v -> v.match.any { fold(it) in named } }?.let { return it.flag }
        }
        return language.flag
    }

    /**
     * Languages matching [query] in their code, English name, native name, brand verb or
     * aliases, ignoring case and accents ("catala" finds "català"). An exact code comes first,
     * then names that start with the query, then names containing it, each in English-name
     * order. A blank query is [all].
     */
    fun search(query: String): List<CatalogLanguage> {
        val q = fold(query)
        if (q.isEmpty()) return sorted
        return keys.mapNotNull { (language, names) ->
            val rank = when {
                language.code == q -> 0
                names.any { name -> name.startsWith(q) || name.split(' ', '-').any { it.startsWith(q) } } -> 1
                names.any { q in it } -> 2
                else -> return@mapNotNull null
            }
            rank to language
        }.sortedBy { it.first }.map { it.second }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Retired ISO 639-1 codes, and CLDR's preferred codes for a few, mapped to the catalog's. */
        private val RETIRED = mapOf(
            "iw" to "he", "in" to "id", "ji" to "yi", "jw" to "jv", "mo" to "ro", "fil" to "tl",
        )

        /** The catalog in [text], the contents of `lab_languages.json`. */
        fun parse(text: String): LanguageCatalog = LanguageCatalog(json.decodeFromString<CatalogFile>(text).languages)

        /** Lower case, without accents or other combining marks: what search compares. */
        fun fold(text: String): String =
            Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
                .replace(COMBINING, "")
                .lowercase(Locale.ROOT)

        private val COMBINING = Regex("\\p{M}+")
    }
}
