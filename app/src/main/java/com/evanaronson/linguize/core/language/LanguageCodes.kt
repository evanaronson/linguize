package com.evanaronson.linguize.core.language

import java.util.Locale

/**
 * ISO 639-1 codes: which two-letter codes are languages, and their names from the platform's
 * locale data (ICU on Android). Pure: no Android, so prompts and tests use the same names.
 */
object LanguageCodes {
    private val SHAPE = Regex("[a-z]{2}")

    /** Codes Java and older Android still report for three languages, and what ISO 639-1 now uses. */
    private val LEGACY = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

    /** Every ISO 639-1 code the platform knows, in their current form, sorted. */
    val all: List<String> by lazy {
        Locale.getISOLanguages().map { LEGACY[it] ?: it }.filter { SHAPE.matches(it) }.distinct().sorted()
    }

    private val known: Set<String> by lazy { all.toSet() }

    /**
     * [code] as a current ISO 639-1 code ("FR" and " fr" read as "fr", "iw" as "he"), or null
     * when it isn't one. History, the corpus, chats and profiles keep codes in this form.
     */
    fun normalize(code: String?): String? {
        val lower = code?.trim()?.lowercase(Locale.ROOT)?.takeIf { SHAPE.matches(it) } ?: return null
        val current = LEGACY[lower] ?: lower
        return current.takeIf { it in known }
    }

    /** Whether [code] is an ISO 639-1 language code, exactly as stored (lowercase, current form). */
    fun isLanguage(code: String?): Boolean = code != null && normalize(code) == code

    private val byEnglishName: Map<String, String> by lazy {
        all.associateBy { englishName(it).lowercase(Locale.ROOT) } + mapOf("castilian" to "es", "valencian" to "ca")
    }

    /**
     * The code for a language named in English as a model writes it ("French", "Spanish
     * (Mexican)", "Catalan, Valencian", or a code), or null when it names none.
     */
    fun fromEnglishName(name: String?): String? {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        normalize(trimmed)?.let { return it }
        val plain = trimmed.substringBefore('(').substringBefore(',').trim().lowercase(Locale.ROOT)
        return byEnglishName[plain]
    }

    /** The language's English name ("French"), as prompts write it; the code itself when the platform has none. */
    fun englishName(code: String): String = displayName(code, Locale.ENGLISH)

    /** The language's name written in [displayIn], capitalized for a list or a label. */
    fun displayName(code: String, displayIn: Locale): String {
        val name = Locale.forLanguageTag(code).getDisplayLanguage(displayIn)
        if (name.isBlank() || name.equals(code, ignoreCase = true)) return code
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(displayIn) else it.toString() }
    }
}

/**
 * Names for a language as the app shows them. The language catalog (brand names such as
 * "Catalanize", flags) implements this and installs itself in [Languages.names]; until then
 * [LocaleLanguageNames] gives the platform's names and no brand.
 */
interface LanguageNames {
    /** The language's name in [displayIn] ("French", "francés"). */
    fun name(code: String, displayIn: Locale): String

    /** The product's name for the language ("Catalanize"), or null when it has none. */
    fun brand(code: String, displayIn: Locale): String? = null
}

/** Names from the platform's locale data, and no brand names. */
object LocaleLanguageNames : LanguageNames {
    override fun name(code: String, displayIn: Locale): String = LanguageCodes.displayName(code, displayIn)
}

/** Where the app looks up language names: the catalog replaces [names] at start-up. */
object Languages {
    @Volatile var names: LanguageNames = LocaleLanguageNames
}
