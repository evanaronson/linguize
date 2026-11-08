package com.evanaronson.linguize.ui.hearit

import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingProfile
import java.util.Locale

/**
 * Which voice to ask for. Text-to-speech and speech recognition both take a locale, and a
 * learner in Barcelona wants es-ES, not the es-US voice many phones default to. The
 * writer's variety (free text in their writing profile, "Peninsular" by default) picks the
 * region; the plain language comes last so any voice for it can still speak.
 */
internal object SpeechLocales {
    /** Spanish regions by words a variety may use, checked in order. */
    private val spanishRegions = listOf(
        listOf("mexic") to listOf("es-MX", "es-419", "es-US"),
        listOf("argentin", "rioplat", "porteñ") to listOf("es-AR", "es-419"),
        listOf("colombi") to listOf("es-CO", "es-419"),
        listOf("chile") to listOf("es-CL", "es-419"),
        listOf("peru", "perú") to listOf("es-PE", "es-419"),
        listOf("venezuel") to listOf("es-VE", "es-419"),
        listOf("urugua") to listOf("es-UY", "es-419"),
        listOf("cuba") to listOf("es-CU", "es-419"),
        listOf("puerto ric") to listOf("es-PR", "es-US", "es-419"),
        listOf("united states", "us spanish", "u.s.") to listOf("es-US", "es-419"),
        listOf("latin", "latam", "south americ", "central americ") to listOf("es-419", "es-US", "es-MX"),
        listOf("peninsular", "spain", "españa", "castil", "european", "iberian", "madrid") to listOf("es-ES"),
    )

    private val catalanRegions = listOf(
        listOf("andorr") to listOf("ca-AD", "ca-ES"),
        // Valencian voices are rare; ca-ES speaks the same written language.
        listOf("valencia") to listOf("ca-ES"),
    )

    /**
     * Locales to try for [languageCode] written in [variety], best first, ending with the
     * plain language. Unknown varieties fall back to the language's home region in Spain,
     * where Linguize's writers live.
     */
    fun candidates(languageCode: String, variety: String?): List<Locale> {
        val words = variety.orEmpty().lowercase(Locale.ROOT)
        val tags = when (languageCode) {
            "es" -> regionFor(words, spanishRegions) ?: listOf("es-ES")
            "ca" -> regionFor(words, catalanRegions) ?: listOf("ca-ES")
            else -> emptyList()
        }
        return (tags + languageCode).map(Locale::forLanguageTag).distinct()
    }

    /** The writer's own language, for Meaning: the UI locale, then its plain language. */
    fun forNative(locale: Locale): List<Locale> =
        listOf(locale, Locale.forLanguageTag(locale.language)).filter { it.language.isNotEmpty() }.distinct()

    private fun regionFor(words: String, regions: List<Pair<List<String>, List<String>>>) =
        regions.firstOrNull { (keys, _) -> keys.any { it in words } }?.second

    /**
     * What to speak or listen in: the language the check was pinned to, else the one the
     * text looks like, else [fallback]; in the writer's variety for that language when
     * their profile has one.
     */
    fun resolve(
        pinned: Language?,
        text: String,
        profiles: List<WritingProfile>,
        fallback: Language = Language.Spanish,
    ): List<Locale> {
        val language = pinned ?: guessLanguage(text) ?: fallback
        val variety = profiles.firstOrNull { it.language == language.code }?.variety ?: language.variety
        return candidates(language.code, variety)
    }
}

/**
 * Catalan or Spanish, from letters and short words only one of them uses. A guess for an
 * auto-detect check, whose detected language the card doesn't keep. Null when the text
 * doesn't lean either way.
 */
internal fun guessLanguage(text: String): Language? {
    val lower = text.lowercase(Locale.ROOT)
    var catalan = 0
    var spanish = 0
    catalan += 3 * Regex("[àèòç]|l·l|ï").findAll(lower).count()
    spanish += 3 * Regex("[ñ¿¡á]").findAll(lower).count()
    for (word in lower.split(Regex("[^\\p{L}·']+"))) {
        if (word in catalanWords) catalan += 2
        if (word in spanishWords) spanish += 2
    }
    return when {
        catalan > spanish -> Language.Catalan
        spanish > catalan -> Language.Spanish
        else -> null
    }
}

private val catalanWords = setOf(
    "i", "amb", "els", "és", "molt", "però", "això", "aquest", "aquesta", "jo", "nosaltres", "vosaltres",
    "vaig", "també", "avui", "demà", "perquè", "sóc", "estic", "dels", "pel", "què", "fer", "tinc", "hi",
    "gràcies", "bon", "dia", "on", "quan", "us", "ens", "seu", "meu", "teu", "aquí", "res", "cap",
)

private val spanishWords = setOf(
    "y", "con", "los", "las", "muy", "pero", "esto", "este", "esta", "yo", "nosotros", "vosotros",
    "también", "hoy", "mañana", "porque", "soy", "estoy", "del", "qué", "hacer", "tengo", "hay",
    "gracias", "buenos", "días", "donde", "cuando", "nos", "su", "mi", "aquí", "nada", "ningún", "es",
)

/**
 * The Try it box after dictation: the spoken message replaces the starter sample or an
 * empty box, and otherwise follows what was already typed.
 */
internal fun mergeDictation(current: String, dictated: String, sample: String): String {
    val spoken = dictated.trim()
    if (spoken.isEmpty()) return current
    return if (current.isBlank() || current.trim() == sample.trim()) spoken else current.trimEnd() + " " + spoken
}
