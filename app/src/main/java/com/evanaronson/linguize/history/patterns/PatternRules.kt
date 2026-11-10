package com.evanaronson.linguize.history.patterns

import com.evanaronson.linguize.core.EditKind

/**
 * A kind of recurring change. [token] is stable: it names the group in saved explanations
 * and in navigation, so never rename one.
 */
enum class PatternKind(val token: String) {
    Accents("accents"),
    InvertedMarks("inverted_marks"),
    Capitals("capitals"),
    Punctuation("punctuation"),
    Elision("elision"),
    TenirQue("tenir_que"),
    SerEstar("ser_estar"),
    PorPara("por_para"),
    Gender("gender"),
    Number("number"),
    Agreement("agreement"),
    Articles("articles"),
    Prepositions("prepositions"),
    Subjunctive("subjunctive"),
    VerbForms("verb_forms"),
    Spelling("spelling"),

    /** Fixes no rule recognised. Listed for completeness, never presented as a pattern. */
    Other("other"),
    ;

    companion object {
        fun read(token: String): PatternKind? = entries.firstOrNull { it.token == token }
    }
}

/**
 * How a change was put in its group. Only [Exact] is a fact about the text: the change
 * itself is, say, accents and nothing else. The others are the analyser's inference.
 */
enum class Basis(val token: String) {
    /** The before and after differ only in the way the group names (accents, capitals, punctuation). */
    Exact("exact"),

    /** Inferred from the words that changed: known word lists and word shapes. */
    Words("words"),

    /** Inferred from keywords in the reason recorded with the change. */
    Reason("reason"),
}

/** The group one change belongs to, and how that was decided. */
data class Classification(val kind: PatternKind, val basis: Basis)

/**
 * Puts one recorded change in a group. Deterministic and offline: the same before, after,
 * reason and language always give the same answer. [language] is the ISO code the change's
 * attempt targeted, or null when unknown (then both Spanish and Catalan rules apply). The word
 * rules know Spanish and Catalan words only; any other language gets the rules that hold in
 * every language: punctuation, capitals, accents, the recorded reason and spelling.
 *
 * Rules run from the most certain to the least: changes the text itself proves, then word
 * rules, then the recorded reason, then closeness of spelling. A rewording ([EditKind.Natural])
 * is only grouped by a specific rule, never as spelling or [PatternKind.Other]: sounding more
 * natural is not a mistake. Returns null for a change that should not be counted.
 */
object PatternRules {
    fun classify(from: String, to: String, why: String?, language: String?, kind: EditKind = EditKind.Fix): Classification? {
        if (normalizeSpacing(from) == normalizeSpacing(to)) return null
        val result = exact(from, to, language) ?: wordRule(from, to, language) ?: reason(why)
        if (result != null) return result
        if (kind != EditKind.Fix) return null
        return spelling(from, to) ?: Classification(PatternKind.Other, Basis.Words)
    }

    private fun normalizeSpacing(text: String) =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).trim().replace(Regex("\\s+"), " ")

    private fun spanish(language: String?) = language == null || language == "es"
    private fun catalan(language: String?) = language == null || language == "ca"

    /** Lab, language-generic: whether the Spanish and Catalan word lists can apply at all. */
    private fun knownWords(language: String?) = spanish(language) || catalan(language)

    /** What the before and after alone prove. */
    private fun exact(from: String, to: String, language: String?): Classification? {
        val a = lettersOnly(from)
        val b = lettersOnly(to)
        if (a == b) {
            val marks = setOf('¿', '¡')
            val addedMarks = to.count { it in marks } > from.count { it in marks }
            val restSame = from.filterNot { it in marks }.filterNot { it.isWhitespace() } ==
                to.filterNot { it in marks }.filterNot { it.isWhitespace() }
            if (addedMarks && restSame && spanish(language)) return Classification(PatternKind.InvertedMarks, Basis.Exact)
            return Classification(PatternKind.Punctuation, Basis.Exact)
        }
        if (a.lowercase() == b.lowercase()) return Classification(PatternKind.Capitals, Basis.Exact)
        if (fold(a) == fold(b)) return Classification(PatternKind.Accents, Basis.Exact)
        return null
    }

    private fun wordRule(from: String, to: String, language: String?): Classification? {
        if (!knownWords(language)) return null
        val before = words(from)
        val after = words(to)
        val change = wordChange(before, after)
        if (change.isEmpty) return null
        val byWords = Classification(PatternKind.Prepositions, Basis.Words)
        if (catalan(language) && tenirQue(before) && !tenirQue(after) &&
            (after.contains("de") || after.contains("d'") || after.any { it == "cal" || it == "caldrà" || it == "calia" })
        ) return byWords.copy(kind = PatternKind.TenirQue)
        if (catalan(language) && elided(change)) return byWords.copy(kind = PatternKind.Elision)
        if (swaps(change, SER, ESTAR)) return byWords.copy(kind = PatternKind.SerEstar)
        if (spanish(language) && swaps(change, setOf("por"), setOf("para"))) return byWords.copy(kind = PatternKind.PorPara)
        if (paired(change, ::genderSwap)) return byWords.copy(kind = PatternKind.Gender)
        if (paired(change, ::numberSwap)) return byWords.copy(kind = PatternKind.Number)
        if (change.changed.all { it in ARTICLES }) return byWords.copy(kind = PatternKind.Articles)
        if (change.changed.all { it in PREPOSITIONS }) return byWords
        return null
    }

    /** "tinc que", "tenia que" and the like: a tenir form followed by que. */
    private fun tenirQue(words: List<String>) =
        words.zipWithNext().any { (a, b) -> a in TENIR && b == "que" }

    /** An elided article or pronoun (l', d', m', ...) appears where the full word was. */
    private fun elided(change: WordChange): Boolean {
        val added = change.added.filter { it.endsWith("'") }
        if (added.isEmpty()) return false
        return added.all { clitic -> change.removed.any { full -> full in ELISIONS[clitic].orEmpty() } } &&
            change.removed.all { full -> added.any { full in ELISIONS[it].orEmpty() } }
    }

    private fun swaps(change: WordChange, a: Set<String>, b: Set<String>): Boolean {
        val removed = change.removed.toSet()
        val added = change.added.toSet()
        return (removed.any { it in a } && added.any { it in b }) || (removed.any { it in b } && added.any { it in a })
    }

    /** Every changed word lines up one to one with a partner, and each pair passes [test]. */
    private fun paired(change: WordChange, test: (String, String) -> Boolean): Boolean {
        if (change.removed.isEmpty() || change.removed.size != change.added.size) return false
        return change.removed.zip(change.added).all { (a, b) -> test(a, b) }
    }

    private fun genderSwap(a: String, b: String): Boolean {
        if (GENDER_PAIRS.any { (m, f) -> (a == m && b == f) || (a == f && b == m) }) return true
        val endings = listOf("os" to "as", "o" to "a", "ns" to "nes", "s" to "es", "" to "a")
        return endings.any { (m, f) ->
            val stemA = a.removeSuffixOrNull(m)
            val stemB = b.removeSuffixOrNull(f)
            val stemA2 = a.removeSuffixOrNull(f)
            val stemB2 = b.removeSuffixOrNull(m)
            (stemA != null && stemA.length >= 2 && stemA == stemB) || (stemA2 != null && stemA2.length >= 2 && stemA2 == stemB2)
        } && !numberSwap(a, b)
    }

    private fun numberSwap(a: String, b: String): Boolean {
        if (NUMBER_PAIRS.any { (s, p) -> (a == s && b == p) || (a == p && b == s) }) return true
        fun plural(s: String, p: String) = s.length >= 2 && (p == s + "s" || p == s + "es")
        return plural(a, b) || plural(b, a)
    }

    private fun String.removeSuffixOrNull(suffix: String): String? =
        if (endsWith(suffix)) dropLast(suffix.length) else null

    /** Keywords in the recorded reason, in English, Spanish or Catalan. Most specific first. */
    private fun reason(why: String?): Classification? {
        if (why.isNullOrBlank()) return null
        val text = fold(why)
        val tokens = words(text).toSet()
        fun has(vararg stems: String) = stems.any { stem -> tokens.any { it.startsWith(stem) } }
        val kind = when {
            has("subjunct", "subjunti") -> PatternKind.Subjunctive
            "ser" in tokens && "estar" in tokens -> PatternKind.SerEstar
            "por" in tokens && "para" in tokens -> PatternKind.PorPara
            has("gender", "genero", "genere", "masculin", "feminin", "femeni") -> PatternKind.Gender
            has("agreement", "concordanc", "agree") -> PatternKind.Agreement
            has("accent", "tilde", "diacritic") -> PatternKind.Accents
            has("preposition", "preposicion", "preposicio") -> PatternKind.Prepositions
            has("article", "articulo") -> PatternKind.Articles
            has("tense", "conjugat", "conjugac", "preterit", "imperfect", "infinitiv", "gerund", "particip") -> PatternKind.VerbForms
            has("spelling", "typo", "misspel", "ortograf") -> PatternKind.Spelling
            has("punctuation", "comma", "puntuac", "coma") -> PatternKind.Punctuation
            has("capital", "mayuscul", "majuscul") -> PatternKind.Capitals
            else -> return null
        }
        return Classification(kind, Basis.Reason)
    }

    /** One word for another that is spelled almost the same: a likely typo or misspelling. */
    private fun spelling(from: String, to: String): Classification? {
        val change = wordChange(words(from), words(to))
        if (change.removed.size != 1 || change.added.size != 1) return null
        val a = fold(change.removed.single())
        val b = fold(change.added.single())
        val longest = maxOf(a.length, b.length)
        if (longest < 3) return null
        return if (levenshtein(a, b) <= maxOf(1, (longest + 1) / 3)) Classification(PatternKind.Spelling, Basis.Words) else null
    }

    private val SER = setOf(
        "ser", "soy", "eres", "es", "somos", "sois", "son", "era", "eras", "eramos", "éramos", "erais", "eran",
        "fui", "fuiste", "fue", "fuimos", "fuisteis", "fueron", "sea", "seas", "seamos", "seáis", "sean",
        "sido", "siendo", "seré", "serás", "será", "seremos", "serán", "sería", "serías", "seríamos", "serían",
        // Catalan
        "sóc", "soc", "ets", "és", "som", "sou", "són", "eren", "sigui", "siguin",
    )
    private val ESTAR = setOf(
        "estar", "estoy", "estás", "está", "estamos", "estáis", "están", "estaba", "estabas", "estábamos",
        "estaban", "estuve", "estuviste", "estuvo", "estuvimos", "estuvieron", "esté", "estés", "estemos",
        "estén", "estado", "estando", "estaré", "estará", "estarán", "estaría", "estarían",
        // Catalan
        "estic", "estàs", "està", "estem", "esteu", "estan", "estava", "estaves", "estàvem", "estaven",
        "estigui", "estiguis", "estiguin", "estat",
    )
    private val TENIR = setOf(
        "tenir", "tinc", "tens", "té", "te", "tenim", "teniu", "tenen", "tenia", "tenies", "teníem",
        "tenien", "tindré", "tindràs", "tindrà", "tindrem", "tindran", "tindria", "tindries", "tindríem",
        "tindrien", "tingut", "tenint", "tingui", "tinguis", "tinguem", "tinguin",
    )
    private val ARTICLES = setOf(
        "el", "la", "los", "las", "lo", "un", "una", "unos", "unas", "al", "del",
        "els", "les", "l'", "uns", "unes", "na", "n'",
    )
    private val PREPOSITIONS = setOf(
        "a", "ante", "bajo", "con", "contra", "de", "desde", "durante", "en", "entre", "hacia", "hasta",
        "mediante", "para", "por", "según", "sin", "sobre", "tras", "al", "del",
        // Catalan
        "amb", "per", "sense", "des", "fins", "cap", "davant", "sota", "segons", "dels", "als", "pels", "pel", "d'",
    )
    /** Catalan elided forms and the full words they replace before a vowel or h. */
    private val ELISIONS = mapOf(
        "l'" to setOf("el", "la", "lo"),
        "d'" to setOf("de"),
        "m'" to setOf("me", "em"),
        "t'" to setOf("te", "et"),
        "s'" to setOf("se", "es"),
        "n'" to setOf("ne", "en"),
    )
    private val GENDER_PAIRS = listOf(
        "el" to "la", "los" to "las", "un" to "una", "unos" to "unas", "este" to "esta", "estos" to "estas",
        "ese" to "esa", "esos" to "esas", "aquel" to "aquella", "aquellos" to "aquellas",
        "nuestro" to "nuestra", "vuestro" to "vuestra", "ninguno" to "ninguna", "algún" to "alguna",
        "ningún" to "ninguna", "buen" to "buena", "mal" to "mala",
        "els" to "les", "uns" to "unes", "aquest" to "aquesta", "aquests" to "aquestes", "aquell" to "aquella",
        "aquells" to "aquelles", "meu" to "meva", "teu" to "teva", "seu" to "seva", "bo" to "bona",
        "nostre" to "nostra", "vostre" to "vostra",
    )
    private val NUMBER_PAIRS = listOf(
        "el" to "los", "la" to "las", "un" to "unos", "una" to "unas", "el" to "els", "la" to "les",
        "un" to "uns", "una" to "unes", "es" to "son", "está" to "están", "és" to "són", "està" to "estan",
        "ha" to "han",
    )
}
