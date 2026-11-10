package com.evanaronson.linguize.ui.notes

/**
 * The sentence a fresh install's Notes editor starts with: something to check before writing
 * anything. Each has one small, typical learner mistake, so the first check shows a fix.
 * It is in the first of the writer's languages that has one, else English. Not translated:
 * the sample is in the language being learned, not the interface language.
 */
internal object NoteSamples {
    const val FALLBACK = "en"

    val byLanguage: Map<String, String> = mapOf(
        "ca" to "Bon dia! Com estas amb la pluja?",
        "es" to "¡Hola! ¿Como estás con este calor?",
        "fr" to "Bonjour ! Tu as passé une bonne week-end ?",
        "de" to "Hallo! Ich habe gestern nach Berlin gefahren.",
        "it" to "Ciao! Ieri ho andato al mare con i miei amici.",
        "pt" to "Olá! Moro em Lisboa desde três anos.",
        "en" to "Hi! I have arrived yesterday and the weather is lovely.",
    )

    /** The sample for the writer's languages [codes], in their order. */
    fun forLanguages(codes: List<String>): String =
        codes.firstNotNullOfOrNull { byLanguage[it.lowercase()] } ?: byLanguage.getValue(FALLBACK)
}
