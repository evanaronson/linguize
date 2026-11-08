package com.evanaronson.linguize.core.languagebridge

import com.evanaronson.linguize.core.Language
import java.util.Locale

/**
 * The writer's own message said in another of their languages, staying close to their
 * wording, with the correspondences that let them learn how one maps onto the other.
 * Not a check: nothing here is an edit of the source, and nothing is recorded.
 */
data class Bridged(
    val status: Status,
    /** The language the model read the message as, by its English name; empty when unclear. */
    val sourceLanguage: String,
    /** The message in the target language; empty unless [status] is [Status.Ok]. */
    val text: String,
    /** Source excerpt to target excerpt, in source order; every excerpt occurs in its text. */
    val pairs: List<Correspondence>,
) {
    enum class Status {
        Ok,

        /** The message is already in the target language: nothing to say differently. */
        SameLanguage,

        /** The model couldn't tell what the message means. */
        Unclear,
    }
}

/** One of the writer's phrases and what it became. */
data class Correspondence(
    /** An exact excerpt of the writer's message. */
    val source: String,
    /** An exact excerpt of [Bridged.text]. */
    val target: String,
    val kind: Kind,
    /** In the writer's own language; empty when nothing needs saying. */
    val note: String,
) {
    enum class Kind(val token: String) {
        /** The same word or phrase in both languages. */
        Same("same"),

        /** A recognisable counterpart: a cognate with different spelling or form. */
        Close("close"),

        /** The wording had to change, because the literal version would be wrong or unnatural. */
        Different("different"),

        /** Looks like a word of the target (or the writer's own) language but means something else. */
        FalseFriend("false_friend"),
        ;

        companion object {
            fun read(token: String): Kind? = entries.firstOrNull { it.token == token }
        }
    }
}

/**
 * Which language a message is probably in, among those Linguize knows, from cheap surface
 * markers. Only used to pick a sensible default target ("Say it in Catalan" under a Spanish
 * message); the model reads the actual language, and says when the target is the same.
 */
object LanguageGuess {
    /** "ca", "es", or null when the markers don't clearly favour one. */
    fun guess(text: String): String? {
        val lower = text.lowercase()
        val words = WORD.findAll(lower).map { it.value }.toList()
        var catalan = words.count { it in CATALAN_WORDS } * 2
        var spanish = words.count { it in SPANISH_WORDS } * 2
        catalan += CATALAN_MARKS.sumOf { mark -> lower.windowed(mark.length).count { it == mark } } * 2
        spanish += SPANISH_MARKS.sumOf { mark -> lower.count { it == mark } } * 2
        // Apostrophised articles and pronouns (l'home, d'aquí, m'agrada) are Catalan.
        catalan += APOSTROPHE.findAll(lower).count()
        return when {
            catalan - spanish >= 2 -> "ca"
            spanish - catalan >= 2 -> "es"
            else -> null
        }
    }

    private val WORD = Regex("""[\p{L}·]+""")
    private val APOSTROPHE = Regex("""\b[ldmtsn]['’]\p{L}""")
    private val CATALAN_MARKS = listOf("l·l", "ç", "à", "è", "ò", "ï", "ny")
    private val SPANISH_MARKS = listOf('ñ', '¿', '¡', 'á')
    private val CATALAN_WORDS = setOf(
        "amb", "és", "els", "per", "però", "què", "molt", "molta", "això", "aquest", "aquesta",
        "avui", "demà", "gràcies", "sóc", "soc", "estic", "tinc", "vull", "també", "doncs", "meu",
        "teu", "nosaltres", "vosaltres", "perquè", "quan", "com", "bon", "bona", "on", "hi", "ho",
        "allà", "res", "gens", "puc", "podem", "fer", "dir", "anar", "vaig", "seu",
        "nostre", "ara", "ja", "sempre", "mai", "dia", "nit", "fins", "llavors", "després",
    )
    private val SPANISH_WORDS = setOf(
        "los", "las", "con", "pero", "muy", "mucho", "mucha", "esto", "este", "esta", "hoy",
        "mañana", "gracias", "soy", "estoy", "tengo", "quiero", "también", "y", "porque", "cuando",
        "como", "buenos", "buenas", "día", "donde", "dónde", "nosotros", "vosotros", "usted",
        "allí", "nada", "puedo", "podemos", "hacer", "decir", "ir", "fui", "su", "nuestro",
        "ahora", "siempre", "nunca", "noche", "hasta", "entonces", "después",
    )
}

/** Picks the language to say a message in. */
object BridgeTargets {
    /** [code]'s English name, as prompts write languages: Linguize's own name when it knows it. */
    fun englishName(code: String): String = Language.forCode(code)?.name
        ?: Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }

    /**
     * The first of [available] (the writer's profile languages, by code) that isn't
     * [source]; with [source] unknown, the first one. Null when there's no other language.
     */
    fun defaultTarget(available: List<String>, source: String?): String? =
        available.firstOrNull { it != source } ?: available.firstOrNull()?.takeIf { source == null }

    /**
     * The languages a finished message could also go to: [available] without the one it
     * was just said in and the one the model read it as ([sourceName], an English name
     * matched against [names]).
     */
    fun others(available: List<String>, current: String, sourceName: String?, names: (String) -> String): List<String> =
        available.filter { code ->
            code != current && (sourceName.isNullOrBlank() || !names(code).equals(sourceName.trim(), ignoreCase = true))
        }
}
