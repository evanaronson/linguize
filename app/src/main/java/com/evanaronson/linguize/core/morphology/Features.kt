package com.evanaronson.linguize.core.morphology

/** Lab: the word classes the morphology tells apart. Anything else is not analysed. */
enum class PartOfSpeech { Verb, Noun, Adjective }

enum class Mood { Indicative, Subjunctive, Imperative, NonFinite }

/**
 * Lab: a verb tense, simple or compound. The analyser of single words produces only simple
 * tenses and the three non-finite forms; compound tenses (haber/haver + participle) and the
 * Catalan periphrastic past (vaig + infinitive) are found across words by the conjugation map.
 * [token] is stable for saved navigation state.
 */
enum class Tense(val mood: Mood, val token: String, val compound: Boolean = false) {
    Present(Mood.Indicative, "present"),
    Preterite(Mood.Indicative, "preterite"),
    Imperfect(Mood.Indicative, "imperfect"),
    Future(Mood.Indicative, "future"),
    Conditional(Mood.Indicative, "conditional"),
    PresentSubjunctive(Mood.Subjunctive, "present_subjunctive"),
    ImperfectSubjunctive(Mood.Subjunctive, "imperfect_subjunctive"),
    Imperative(Mood.Imperative, "imperative"),
    Infinitive(Mood.NonFinite, "infinitive"),
    Gerund(Mood.NonFinite, "gerund"),
    Participle(Mood.NonFinite, "participle"),
    Perfect(Mood.Indicative, "perfect", compound = true),
    Pluperfect(Mood.Indicative, "pluperfect", compound = true),
    FuturePerfect(Mood.Indicative, "future_perfect", compound = true),
    ConditionalPerfect(Mood.Indicative, "conditional_perfect", compound = true),
    PerfectSubjunctive(Mood.Subjunctive, "perfect_subjunctive", compound = true),
    PluperfectSubjunctive(Mood.Subjunctive, "pluperfect_subjunctive", compound = true),
    /** Catalan "vaig anar": a past perfective made of anar's present and an infinitive. */
    PeriphrasticPast(Mood.Indicative, "periphrastic_past", compound = true),
    ;

    val finite: Boolean get() = mood != Mood.NonFinite

    companion object {
        fun read(token: String): Tense? = entries.firstOrNull { it.token == token }
    }
}

enum class GrammaticalNumber { Singular, Plural }

/** Lab: grammatical person. Usted and ustedes are third person, as their verbs are. */
enum class Person(val token: String, val person: Int, val number: GrammaticalNumber) {
    S1("1s", 1, GrammaticalNumber.Singular),
    S2("2s", 2, GrammaticalNumber.Singular),
    S3("3s", 3, GrammaticalNumber.Singular),
    P1("1p", 1, GrammaticalNumber.Plural),
    P2("2p", 2, GrammaticalNumber.Plural),
    P3("3p", 3, GrammaticalNumber.Plural),
    ;

    companion object {
        fun read(token: String): Person? = entries.firstOrNull { it.token == token }
    }
}

/**
 * Lab: one reading of a word. A word usually has several ("cante": present subjunctive, first
 * or third person), so the analyser returns sets. [tense] and [person] are set for verbs;
 * [person] is null for non-finite forms. [guessed] marks a reading made by rule for a verb
 * that is not in the bundled lists. [enclitic] marks a host that carried attached pronouns
 * ("dímelo", "dóna-m'ho").
 */
data class Analysis(
    val lemma: String,
    val pos: PartOfSpeech,
    val tense: Tense? = null,
    val person: Person? = null,
    val guessed: Boolean = false,
    val enclitic: Boolean = false,
) {
    val mood: Mood? get() = tense?.mood
    val number: GrammaticalNumber? get() = person?.number
}

/** One generated verb form and what it is. */
internal data class VerbForm(val form: String, val tense: Tense, val person: Person?)

internal val SIX = Person.entries

/** Splits a row written as six space-separated forms; "-" marks a form the verb lacks. */
internal fun row(forms: String): List<String?> =
    forms.trim().split(Regex("\\s+")).map { it.takeUnless { form -> form == "-" } }.also {
        require(it.size == 6) { "A row has six forms: $forms" }
    }

internal fun MutableList<VerbForm>.addRow(tense: Tense, forms: List<String?>) {
    forms.forEachIndexed { index, form -> if (form != null) add(VerbForm(form, tense, SIX[index])) }
}

private const val PLAIN = "aeiou"
private const val ACUTE = "áéíóú"

internal fun accentLastVowel(stem: String): String {
    val index = stem.indexOfLast { it in PLAIN }
    if (index < 0) return stem
    return stem.substring(0, index) + ACUTE[PLAIN.indexOf(stem[index])] + stem.substring(index + 1)
}

/** Removes acute and grave accents (not ü, ï or ç). */
internal fun stripAccents(word: String): String = buildString(word.length) {
    for (c in word) append(
        when (c) {
            'á', 'à' -> 'a'
            'é', 'è' -> 'e'
            'í', 'ì' -> 'i'
            'ó', 'ò' -> 'o'
            'ú', 'ù' -> 'u'
            else -> c
        },
    )
}

internal fun Char.isVowel(): Boolean = this in "aeiouáéíóúàèòïü"
