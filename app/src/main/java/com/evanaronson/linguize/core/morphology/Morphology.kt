package com.evanaronson.linguize.core.morphology

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos

/**
 * Lab: Spanish and Catalan word analysis, offline. Every verb in the bundled lexicons is
 * conjugated once into a form → readings table (built on first use per language), and listed
 * adjectives are inflected into it too. A word is looked up as written; failing that, attached
 * pronouns are stripped ("dímelo", "dóna-m'ho", "l'he"); failing that, regular endings are
 * guessed for verbs not in the lexicon, and plurals are folded for nouns. Ambiguity is kept:
 * "fui" is both ir and ser.
 */
class Morphology {
    private val tables = mapOf(
        "es" to lazy { build("es") },
        "ca" to lazy { build("ca") },
    )

    fun supports(language: String): Boolean = language in tables

    /** All readings of a lowercase [word]; empty when nothing is known or guessed. */
    fun analyse(word: String, language: String): Set<Analysis> {
        val table = tables[language]?.value ?: return emptySet()
        val w = word.lowercase().replace('’', '\'')
        if (w.isEmpty()) return emptySet()
        table[w]?.let { return it }
        val clitic = when (language) {
            "es" -> spanishEnclitics(w, table)
            else -> catalanClitics(w, table)
        }
        if (clitic != null) return clitic
        val host = if (language == "ca") catalanHost(w) else w
        return guessVerb(host, language) + guessNominal(host, language)
    }

    /** Verb readings only, from [analyse]. */
    fun verbs(word: String, language: String): Set<Analysis> =
        analyse(word, language).filterTo(mutableSetOf()) { it.pos == PartOfSpeech.Verb }

    /** Whether the bundled lexicon lists [lemma] as a verb of [language]. */
    fun knowsVerb(lemma: String, language: String): Boolean = when (language) {
        "es" -> Spanish.lexicon.any { it.lemma == lemma }
        "ca" -> Catalan.lexicon.any { it.lemma == lemma }
        else -> false
    }

    /** Every form of a known verb, for tests and examples. */
    internal fun paradigm(lemma: String, language: String): List<VerbForm> = when (language) {
        "es" -> Spanish.lexicon.firstOrNull { it.lemma == lemma }?.let(Spanish::conjugate) ?: Spanish.conjugate(Spanish.Spec(lemma))
        "ca" -> Catalan.lexicon.firstOrNull { it.lemma == lemma }?.let(Catalan::conjugate) ?: Catalan.conjugate(Catalan.Spec(lemma))
        else -> emptyList()
    }

    private fun build(language: String): Map<String, Set<Analysis>> {
        val table = HashMap<String, MutableSet<Analysis>>(16384)
        fun add(form: String, analysis: Analysis) {
            table.getOrPut(form) { mutableSetOf() } += analysis
        }
        val verbs: List<Pair<String, List<VerbForm>>> = when (language) {
            "es" -> Spanish.lexicon.map { it.lemma to Spanish.conjugate(it) }
            else -> Catalan.lexicon.map { it.lemma to Catalan.conjugate(it) }
        }
        verbs.forEach { (lemma, forms) ->
            forms.forEach { add(it.form, Analysis(lemma, PartOfSpeech.Verb, it.tense, it.person)) }
        }
        Nominal.adjectiveForms(language).forEach { (form, lemma) -> add(form, Analysis(lemma, PartOfSpeech.Adjective)) }
        return table
    }

    /**
     * Spanish pronouns attach to infinitives, gerunds and affirmative imperatives: hacerlo,
     * diciéndome, dímelo, vámonos, sentaos. A subjunctive host with pronouns is an imperative
     * (dígamelo, hagámoslo), so it is read as one.
     */
    private fun spanishEnclitics(word: String, table: Map<String, Set<Analysis>>): Set<Analysis>? {
        val found = mutableSetOf<Analysis>()
        for (first in SPANISH_CLITICS) {
            if (!word.endsWith(first)) continue
            val once = word.dropLast(first.length)
            tryHost(once, first, table, found)
            for (second in SPANISH_CLITICS) {
                if (once.endsWith(second)) tryHost(once.dropLast(second.length), second + first, table, found)
            }
        }
        return found.ifEmpty { null }
    }

    private fun tryHost(host: String, clitics: String, table: Map<String, Set<Analysis>>, into: MutableSet<Analysis>) {
        if (host.length < 2) return
        val candidates = buildList {
            // sentaos is sentad + os; vámonos is vamos + nos.
            if (clitics == "os") {
                add(stripAccents(host) + "d")
                // quedaros keeps the infinitive's r.
                if (host.endsWith("d") || host.endsWith("r")) add(host)
                return@buildList
            }
            add(host)
            add(stripAccents(host))
            // mantenlo is mantén + lo; deme is dé + me.
            add(accentLastVowel(host))
            if (clitics.startsWith("nos") && host.endsWith("mo")) add(stripAccents(host) + "s")
        }.distinct()
        candidates.forEach { candidate ->
            table[candidate]?.forEach { analysis -> encliticHost(analysis)?.let { into += it } }
        }
    }

    /** What a host with attached pronouns can be; null when it can't carry them. */
    private fun encliticHost(analysis: Analysis): Analysis? {
        if (analysis.pos != PartOfSpeech.Verb) return null
        return when (analysis.tense) {
            Tense.Infinitive, Tense.Gerund, Tense.Imperative -> analysis.copy(enclitic = true)
            Tense.PresentSubjunctive -> if (analysis.person in IMPERATIVE_FROM_SUBJUNCTIVE) {
                analysis.copy(tense = Tense.Imperative, enclitic = true)
            } else null
            else -> null
        }
    }

    /**
     * Catalan writes pronouns with hyphens and apostrophes. Elided pronouns and articles before
     * the word (l'he, m'agrada, d'anar) leave the word as it is; pronouns after it
     * (dóna-m'ho, fer-ho, posa't) make it an infinitive, gerund or imperative.
     */
    private fun catalanClitics(word: String, table: Map<String, Set<Analysis>>): Set<Analysis>? {
        val parts = word.split('-', '\'').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        var index = 0
        while (index < parts.size - 1 && parts[index] in CATALAN_PROCLITICS) index++
        val host = parts[index]
        val after = parts.drop(index + 1)
        if (!after.all { it in CATALAN_ENCLITICS }) return null
        val readings = table[host] ?: return null
        return if (after.isEmpty()) readings
        else readings.mapNotNullTo(mutableSetOf(), ::encliticHost).ifEmpty { null }
    }

    private fun catalanHost(word: String): String {
        val parts = word.split('-', '\'').filter { it.isNotEmpty() }
        if (parts.size < 2) return word
        return parts.firstOrNull { it !in CATALAN_PROCLITICS } ?: word
    }

    /**
     * Regular endings for verbs not in the lexicon, kept to endings of four letters or more
     * on a stem of three or more ("programaron", "chateando"), so nouns aren't read as verbs.
     */
    private fun guessVerb(word: String, language: String): Set<Analysis> {
        val endings = GUESS_ENDINGS[language]?.value ?: return emptySet()
        val out = mutableSetOf<Analysis>()
        for ((ending, readings) in endings) {
            if (!word.endsWith(ending) || word.length - ending.length < 3) continue
            val stem = word.dropLast(ending.length)
            readings.forEach { (infinitiveEnding, form) ->
                out += Analysis(stem + infinitiveEnding, PartOfSpeech.Verb, form.tense, form.person, guessed = true)
            }
        }
        return out
    }

    private fun guessNominal(word: String, language: String): Set<Analysis> {
        val singular = Nominal.singular(word, language) ?: return emptySet()
        return setOf(Analysis(singular, PartOfSpeech.Noun, guessed = true))
    }

    private companion object {
        val SPANISH_CLITICS = listOf("me", "te", "se", "nos", "os", "lo", "la", "le", "los", "las", "les")
        val IMPERATIVE_FROM_SUBJUNCTIVE = setOf(Person.S3, Person.P1, Person.P3)
        val CATALAN_PROCLITICS = setOf("l", "d", "m", "t", "s", "n", "qu", "ens", "us", "els", "em", "et", "es", "en")
        val CATALAN_ENCLITICS = setOf(
            "me", "te", "se", "lo", "la", "los", "les", "li", "nos", "vos", "ho", "hi", "ne", "en",
            "m", "t", "s", "l", "ls", "n", "ns", "us", "hi", "ho",
        )

        /** ending → (infinitive ending, what the form is), from the regular paradigms of a placeholder stem. */
        val GUESS_ENDINGS: Map<String, Lazy<List<Pair<String, List<Pair<String, VerbForm>>>>>> = mapOf(
            "es" to lazy {
                endings(listOf("ar", "er", "ir")) { Spanish.conjugate(Spanish.Spec("qqq$it")) }
            },
            "ca" to lazy {
                endings(listOf("ar", "re", "ir")) { Catalan.conjugate(Catalan.Spec("qqq$it")) }
            },
        )

        fun endings(classes: List<String>, conjugate: (String) -> List<VerbForm>): List<Pair<String, List<Pair<String, VerbForm>>>> {
            val byEnding = LinkedHashMap<String, MutableList<Pair<String, VerbForm>>>()
            classes.forEach { cls ->
                conjugate(cls).forEach { form ->
                    if (form.form.startsWith("qqq")) {
                        val ending = form.form.removePrefix("qqq")
                        if (ending.length >= 4) byEnding.getOrPut(ending) { mutableListOf() } += cls to form
                    }
                }
            }
            return byEnding.map { (ending, readings) -> ending to readings.distinct() }
                .sortedByDescending { it.first.length }
        }
    }
}

/**
 * Lab: the morphology as `App.lemmatizer`, returning lexemes (lemma and part of speech).
 *
 * - Function words come from a closed-class table, every homograph kept: "la" is the article
 *   "el" and the pronoun "ella".
 * - Verb forms are the verb's infinitive, [Pos.Verb]; listed adjectives fold to the masculine
 *   singular, [Pos.Adjective]; a word that only looks plural folds to a singular, [Pos.Noun].
 * - Known readings win over guessed ones. A guessed verb counts only as a finite form, since
 *   "comando" is not a gerund of "comar".
 * - Words that are verb forms on paper but nearly always something else ("como", "casa") drop
 *   the verb; common nouns that are also verb forms ("trabajo", "dona") keep both.
 * - A word nothing is known about is its own lemma, [Pos.Unknown].
 */
class MorphologyLemmatizer(
    private val morphology: Morphology,
    /**
     * An optional form → lexemes table per language (for example a bundled frequency list),
     * consulted when the morphology knows the word only by guessing, or not at all.
     */
    private val extraForms: (language: String) -> Map<String, Set<Lexeme>> = { emptyMap() },
) : Lemmatizer {
    override fun lexemes(word: String, language: String): Set<Lexeme> {
        val w = word.lowercase().replace('’', '\'')
        if (w.isEmpty() || !morphology.supports(language)) {
            return extraForms(language)[w] ?: setOf(Lexeme(w, Pos.Unknown))
        }
        val out = linkedSetOf<Lexeme>()
        ClosedClass.lexemes(w, language)?.let { out += it }
        val notVerb = w in FunctionWords.notVerbs(language)
        val analyses = morphology.analyse(w, language)
            .filterNot { notVerb && it.pos == PartOfSpeech.Verb }
        val known = analyses.filterNot { it.guessed }
        if (known.isEmpty() && out.isEmpty()) extraForms(language)[w]?.let { return it }
        val chosen = known.ifEmpty {
            if (out.isNotEmpty()) emptyList()
            else analyses.filter { it.pos != PartOfSpeech.Verb || it.tense?.finite == true }
        }
        chosen.forEach { out += Lexeme(it.lemma, it.pos.toPos()) }
        if (w in FunctionWords.alsoNouns(language) || (notVerb && out.isEmpty())) out += Lexeme(w, Pos.Noun)
        if (out.isEmpty() && isMentAdverb(w, language)) out += Lexeme(w, Pos.Adverb)
        return out.ifEmpty { setOf(Lexeme(w, Pos.Unknown)) }
    }

    /**
     * Adverbs in -mente / -ment are their own lemma. Catalan nouns end in -ment too (moment,
     * pagament), so there the part before it must look like a feminine adjective.
     */
    private fun isMentAdverb(word: String, language: String): Boolean = when (language) {
        "es" -> word.length >= 8 && word.endsWith("mente")
        "ca" -> word.length >= 8 && word.endsWith("ment") && word.dropLast(4).let { stem ->
            morphology.analyse(stem, "ca").any { it.pos == PartOfSpeech.Adjective && !it.guessed } ||
                ADVERB_STEMS.any { stem.endsWith(it) }
        }
        else -> false
    }

    private companion object {
        val ADVERB_STEMS = listOf("al", "ble", "il", "ica", "ida", "ada", "ata", "iva", "osa", "ura", "cta", "ia", "ple")
    }
}

private fun PartOfSpeech.toPos(): Pos = when (this) {
    PartOfSpeech.Verb -> Pos.Verb
    PartOfSpeech.Noun -> Pos.Noun
    PartOfSpeech.Adjective -> Pos.Adjective
}

/** Lab: small word lists the analyser and the conjugation map share. */
object FunctionWords {
    /** Words that are verb forms on paper but nearly always something else. */
    fun notVerbs(language: String): Set<String> = when (language) {
        "es" -> SPANISH_NOT_VERBS
        "ca" -> CATALAN_NOT_VERBS
        else -> emptySet()
    }

    /** Common nouns that are also verb forms; the lemmatizer keeps both. */
    fun alsoNouns(language: String): Set<String> = when (language) {
        "es" -> setOf("trabajo", "paso", "cuento", "vino", "cambio", "viaje", "regalo", "pago", "baile", "canto", "llamada", "ayuda", "cena", "duda", "visita", "vuelta", "compra", "espera", "lucha", "pregunta", "respuesta", "beso", "abrazo", "uso", "gasto", "sueño", "juego", "plan", "dato", "nota")
        "ca" -> setOf("dona", "porta", "treball", "pas", "canvi", "viatge", "regal", "ajuda", "pregunta", "resposta", "visita", "compra", "joc", "somni", "petó", "abraçada", "nota", "sopar", "dinar", "esmorzar", "berenar", "deure", "ball", "cant", "pagament", "dubte")
        else -> emptySet()
    }

    /** Words after which a verb form is read as a noun or adjective (articles, determiners, prepositions). */
    fun nominalContext(language: String): Set<String> = when (language) {
        "es" -> setOf("el", "un", "una", "unos", "unas", "mi", "mis", "tu", "tus", "su", "sus", "este", "esta", "estos", "estas", "ese", "esa", "esos", "esas", "aquel", "aquella", "nuestro", "nuestra", "vuestro", "vuestra", "del", "al", "de", "en", "con", "sin", "por", "para", "cada", "otro", "otra", "mucho", "mucha", "poco", "poca", "buen", "buena", "mal", "mala", "gran", "primer", "primera", "último", "última")
        "ca" -> setOf("un", "una", "uns", "unes", "meu", "meva", "teu", "teva", "seu", "seva", "aquest", "aquesta", "aquests", "aquestes", "aquell", "aquella", "del", "al", "pel", "dels", "als", "pels", "de", "d", "amb", "sense", "per", "cada", "altre", "altra", "molt", "molta", "poc", "poca", "bon", "bona", "mal", "mala", "gran", "primer", "primera", "últim", "última")
        else -> emptySet()
    }

    private val SPANISH_NOT_VERBS = setOf(
        "como", "para", "entre", "sobre", "bajo", "solo", "nada", "casa", "cerca", "medio", "claro", "mientras", "cara",
        "pesar", "pena", "hola", "bueno", "mismo", "media", "río", "seguro", "cosa", "ojo", "hora", "fin", "alto",
    )
    private val CATALAN_NOT_VERBS = setOf(
        "com", "sobre", "entre", "casa", "mig", "cara", "pena", "hola", "bé", "bo", "mateix", "segur", "cosa", "hora",
        "fi", "alt", "molt", "tot", "sol", "fins", "mentre",
    )
}
