package com.evanaronson.linguize.core.words

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.corpus.WriterGaps
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Lab, Your words: one time the writer typed a word. */
data class WordUse(
    /** The [Utterance.id] it came from: "check:<id>", "chat:<id>". */
    val utteranceId: String,
    val source: String,
    val at: Long,
    val start: Int,
    val end: Int,
    /** Inside a span Linguize flagged as wrong. The name is kept for the APIs that read it. */
    val tried: Boolean,
)

/** The three levels the words can be counted at. */
enum class WordLevel(val token: String) {
    /** Each spelling as written: "vaig", "anem", "la". */
    Forms("f"),
    /** A lemma with its part of speech: "anar" (verb); "la" is "el" (article) and "ella" (pronoun). */
    Lexemes("x"),
    /** A dictionary form, whatever its part of speech. */
    Lemmas("l"),
    ;

    companion object {
        fun read(token: String?): WordLevel? = entries.firstOrNull { it.token == token }
    }
}

/** What the word list shows for a word form, a lexeme or a lemma. */
interface WordCounts {
    /** Unique within its level: the form, "lemma/pos" for a lexeme, or the lemma. */
    val key: String
    /** What the row shows, searched and sorted by: the form or the lemma. */
    val label: String get() = key
    /** Uses outside corrected spans: used right. */
    val right: Int
    /**
     * Uses inside corrected spans: Linguize flagged them as wrong. Not attempts: [right] and
     * [tried] never overlap, so a word used right 12 times and wrong 3 times was used 15 times.
     */
    val tried: Int
    val firstAt: Long
    val lastAt: Long
    /** First use outside a corrected span; null while the word has only been tried. */
    val firstRightAt: Long?
    /** Distinct pieces of writing it appears in. */
    val messages: Int
    /** The newest uses, newest first, at most [WordIndex.MAX_EXAMPLES]. */
    val recent: List<WordUse>
    val uses: Int get() = right + tried
    val known: Boolean get() = right > 0
    /** The same count as [tried], under the name the page shows: used wrong. */
    val wrong: Int get() = tried
    /** Percent of uses that were right; null with no uses ([WordAccuracy]). */
    val accuracy: Int? get() = WordAccuracy.percent(right, tried)
}

/** One word form as written (lowercase): "vaig", "anem". */
data class WordEntry(
    val form: String,
    /** Every lexeme the lemmatizer gives it; just ([form], unknown) when it knows no morphology. */
    val lexemes: Set<Lexeme>,
    override val right: Int,
    override val tried: Int,
    override val firstAt: Long,
    override val lastAt: Long,
    override val firstRightAt: Long?,
    override val messages: Int,
    override val recent: List<WordUse>,
) : WordCounts {
    override val key: String get() = form

    /** Its dictionary forms, ignoring part of speech. */
    val lemmas: Set<String> get() = lexemes.mapTo(LinkedHashSet()) { it.lemma }

    /** The form could be more than one lexeme, and counts toward each of them. */
    val ambiguous: Boolean get() = lexemes.size > 1
}

/** The key of a lexeme in the index and in navigation: "el/art". */
fun Lexeme.key(): String = "$lemma/${pos.token}"

/**
 * One lexeme (a lemma with its part of speech) and the forms of it the writer typed:
 * "anar" (verb) from "vaig", "anem". Homographs stay apart: "la" counts toward "el"
 * (article) and "ella" (pronoun), and is listed in [ambiguousForms] of both.
 */
data class LexemeEntry(
    val lexeme: Lexeme,
    /** Most used first. */
    val forms: List<String>,
    /** Forms that could also be another lexeme, so they count toward each. */
    val ambiguousForms: Set<String>,
    override val right: Int,
    override val tried: Int,
    override val firstAt: Long,
    override val lastAt: Long,
    override val firstRightAt: Long?,
    override val messages: Int,
    override val recent: List<WordUse>,
) : WordCounts {
    override val key: String get() = lexeme.key()
    override val label: String get() = lexeme.lemma
}

/** One dictionary form and every form of it the writer typed: "anar" from "vaig", "anem". */
data class LemmaEntry(
    val lemma: String,
    /** The lexemes grouped under it, most used first: "el" (article), "el" (pronoun). */
    val lexemes: List<Lexeme>,
    /** Most used first. */
    val forms: List<String>,
    override val right: Int,
    override val tried: Int,
    override val firstAt: Long,
    override val lastAt: Long,
    override val firstRightAt: Long?,
    override val messages: Int,
    override val recent: List<WordUse>,
) : WordCounts {
    override val key: String get() = lemma
}

/** A name left out of the counts: shown as most often written. */
data class NameEntry(val written: String, val count: Int)

/** Distinct known words by a day: the growth chart's points. */
data class GrowthPoint(val day: LocalDate, val distinct: Int)

/** Everything the writer typed in one language. */
data class LanguageWords(
    /** ISO 639-1 code, or null for text whose language wasn't known. */
    val language: String?,
    /** Most used first. */
    val words: List<WordEntry>,
    val lexemes: List<LexemeEntry>,
    val lemmas: List<LemmaEntry>,
    val names: List<NameEntry>,
    /** Every counted word typed, right or tried, names left out. */
    val totalWords: Int,
    /** Words typed this calendar month. */
    val wordsThisMonth: Int,
    /** Forms first used right this calendar month. */
    val newThisMonth: Int,
    /** Pieces of writing counted. */
    val messages: Int,
    /** One point per day the number of known forms grew, oldest first. */
    val growth: List<GrowthPoint>,
    /** The writer's own texts, by utterance id, for contexts. */
    val texts: Map<String, Utterance>,
    /** Every use of each form, oldest first: what a deck's coverage is counted from. */
    val uses: Map<String, List<WordUse>> = emptyMap(),
) {
    companion object {
        /** A language with a deck but nothing written in it yet. */
        fun empty(language: String?) = LanguageWords(
            language, emptyList(), emptyList(), emptyList(), emptyList(), 0, 0, 0, 0, emptyList(), emptyMap(),
        )
    }

    /** Forms used right at least once. */
    val distinctWords: Int get() = words.count { it.known }
    val distinctLexemes: Int get() = lexemes.count { it.known }
    val distinctLemmas: Int get() = lemmas.count { it.known }

    /**
     * Whether the lemmatizer knows morphology here (any part of speech other than unknown):
     * only then are lexemes and lemmas worth showing.
     */
    val morphology: Boolean get() = words.any { word -> word.lexemes.any { it.pos != Pos.Unknown } }

    /** Forms that count toward more than one lexeme. */
    val ambiguousForms: Int get() = words.count { it.ambiguous }

    fun word(form: String): WordEntry? = words.firstOrNull { it.form == form }
    fun lexeme(key: String): LexemeEntry? = lexemes.firstOrNull { it.key == key }
    fun lemma(lemma: String): LemmaEntry? = lemmas.firstOrNull { it.lemma == lemma }

    /** The rows of [level]. */
    fun rows(level: WordLevel): List<WordCounts> = when (level) {
        WordLevel.Forms -> words
        WordLevel.Lexemes -> lexemes
        WordLevel.Lemmas -> lemmas
    }

    fun entry(level: WordLevel, key: String): WordCounts? = when (level) {
        WordLevel.Forms -> word(key)
        WordLevel.Lexemes -> lexeme(key)
        WordLevel.Lemmas -> lemma(key)
    }
}

/** The word index over the whole corpus, one entry per language, most used language first. */
data class WordIndex(val languages: List<LanguageWords>, val fingerprint: String) {
    fun language(code: String?): LanguageWords? = languages.firstOrNull { it.language == code }

    companion object {
        const val MAX_EXAMPLES = 30

        val Empty = WordIndex(emptyList(), CorpusFingerprint.of(emptyList()))

        /**
         * Builds the index from [utterances]. Words inside [Utterance.corrected] spans count as
         * tried, never as known. Names are left out of the counts.
         */
        fun build(
            utterances: List<Utterance>,
            lemmatizer: Lemmatizer,
            now: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): WordIndex {
            val languages = utterances.groupBy { it.language }
                .map { (language, group) -> buildLanguage(language, group.sortedBy { it.at }, lemmatizer, now, zone) }
                .filter { it.totalWords > 0 || it.names.isNotEmpty() }
                .sortedByDescending { it.totalWords }
            return WordIndex(languages, CorpusFingerprint.of(utterances))
        }

        private class Occurrence(val utterance: Utterance, val token: WordToken, val tried: Boolean) {
            fun use() = WordUse(utterance.id, utterance.source, utterance.at, token.start, token.end, tried)
        }

        private fun buildLanguage(
            language: String?,
            utterances: List<Utterance>,
            lemmatizer: Lemmatizer,
            now: Long,
            zone: ZoneId,
        ): LanguageWords {
            val occurrences = utterances.flatMap { utterance ->
                WordTokenizer.tokenize(utterance.text).map { Occurrence(utterance, it, utterance.isCorrected(it.start, it.end)) }
                    // A corrected English word is most likely a gap the check filled, not a word tried.
                    .filterNot { it.tried && WriterGaps.isEnglishGap(it.token.form, language) }
            }
            // A form only ever capitalized, and capitalized in mid-sentence at least once, is a name
            // even at the start of a sentence. Mid-sentence capitals are names wherever they stand.
            val lowercaseForms = occurrences.filter { !it.token.name && !it.token.capitalizedAtStart }.map { it.token.form }.toSet()
            val nameForms = occurrences.filter { it.token.name }.map { it.token.form }.toSet() - lowercaseForms
            val (names, words) = occurrences.partition { it.token.name || (it.token.capitalizedAtStart && it.token.form in nameForms) }

            val month = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone))
            fun inMonth(at: Long) = YearMonth.from(Instant.ofEpochMilli(at).atZone(zone)) == month

            val byForm = words.groupBy({ it.token.form }) { it.use() }
            val entries = byForm.map { (form, uses) ->
                val counts = UseCounts.of(uses)
                WordEntry(
                    form = form,
                    lexemes = lemmatizer.lexemes(form, language.orEmpty()).ifEmpty { setOf(Lexeme(form, Pos.Unknown)) },
                    right = counts.right, tried = counts.tried, firstAt = counts.firstAt, lastAt = counts.lastAt,
                    firstRightAt = counts.firstRightAt, messages = counts.messages, recent = counts.recent,
                )
            }.sortedWith(compareByDescending<WordEntry> { it.uses }.thenBy { it.form })

            // An ambiguous form counts toward every candidate lexeme (and every candidate lemma once).
            val formOrder = compareByDescending<WordEntry> { it.uses }.thenBy { it.form }
            val formsByLexeme = entries.flatMap { entry -> entry.lexemes.map { it to entry } }.groupBy({ it.first }, { it.second })
            val lexemes = formsByLexeme.map { (lexeme, forms) ->
                val counts = UseCounts.of(forms.flatMap { byForm[it.form].orEmpty() })
                LexemeEntry(
                    lexeme = lexeme,
                    forms = forms.sortedWith(formOrder).map { it.form },
                    ambiguousForms = forms.filter { it.ambiguous }.mapTo(LinkedHashSet()) { it.form },
                    right = counts.right, tried = counts.tried, firstAt = counts.firstAt, lastAt = counts.lastAt,
                    firstRightAt = counts.firstRightAt, messages = counts.messages, recent = counts.recent,
                )
            }.sortedWith(compareByDescending<LexemeEntry> { it.uses }.thenBy { it.key })

            val lexemesByLemma = lexemes.groupBy({ it.lexeme.lemma }) { it.lexeme }
            val formsByLemma = entries.flatMap { entry -> entry.lemmas.map { it to entry } }.groupBy({ it.first }, { it.second })
            val lemmas = formsByLemma.map { (lemma, forms) ->
                val counts = UseCounts.of(forms.flatMap { byForm[it.form].orEmpty() })
                LemmaEntry(
                    lemma = lemma,
                    lexemes = lexemesByLemma[lemma].orEmpty(),
                    forms = forms.sortedWith(formOrder).map { it.form },
                    right = counts.right, tried = counts.tried, firstAt = counts.firstAt, lastAt = counts.lastAt,
                    firstRightAt = counts.firstRightAt, messages = counts.messages, recent = counts.recent,
                )
            }.sortedWith(compareByDescending<LemmaEntry> { it.uses }.thenBy { it.lemma })

            val nameEntries = names.groupBy { it.token.form }.map { (_, uses) ->
                val written = uses.groupingBy { it.token.written }.eachCount().maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key }).key
                NameEntry(written, uses.size)
            }.sortedWith(compareByDescending<NameEntry> { it.count }.thenBy { it.written })

            val growth = entries.mapNotNull { it.firstRightAt }
                .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                .groupingBy { it }.eachCount().toSortedMap()
                .let { perDay ->
                    var total = 0
                    perDay.map { (day, count) -> total += count; GrowthPoint(day, total) }
                }

            return LanguageWords(
                language = language,
                words = entries,
                lexemes = lexemes,
                lemmas = lemmas,
                names = nameEntries,
                totalWords = words.size,
                wordsThisMonth = words.count { inMonth(it.utterance.at) },
                newThisMonth = entries.count { entry -> entry.firstRightAt?.let(::inMonth) == true },
                messages = words.map { it.utterance.id }.toSet().size,
                growth = growth,
                texts = utterances.associateBy { it.id },
                uses = byForm,
            )
        }
    }
}

/** The same occurrence summary underlies forms, lexemes, lemmas and deck rows. */
internal class UseCounts(
    val right: Int, val tried: Int, val firstAt: Long, val lastAt: Long, val firstRightAt: Long?,
    val messages: Int, val recent: List<WordUse>,
) {
    companion object {
        fun of(uses: List<WordUse>): UseCounts {
            val right = uses.filter { !it.tried }
            return UseCounts(
                right = right.size,
                tried = uses.size - right.size,
                firstAt = uses.minOf { it.at },
                lastAt = uses.maxOf { it.at },
                firstRightAt = right.minOfOrNull { it.at },
                messages = uses.map { it.utteranceId }.toSet().size,
                recent = uses.sortedWith(compareByDescending<WordUse> { it.at }.thenBy { it.start }).take(WordIndex.MAX_EXAMPLES),
            )
        }
    }
}

/**
 * Identifies a corpus's content, so a cached index is rebuilt when any input is added,
 * edited or deleted. Order-independent.
 */
object CorpusFingerprint {
    fun of(utterances: List<Utterance>): String {
        var hash = OFFSET
        var count = 0L
        for (utterance in utterances.sortedBy { it.id }) {
            hash = mix(hash, utterance.id)
            hash = mix(hash, utterance.language.orEmpty())
            hash = mix(hash, utterance.at.toString())
            hash = mix(hash, utterance.text)
            hash = mix(hash, utterance.corrected.joinToString(",") { "${it.start}-${it.end}" })
            count++
        }
        return "$count:${java.lang.Long.toHexString(hash)}"
    }

    private fun mix(start: Long, value: String): Long {
        var hash = start
        for (c in value) {
            hash = hash xor c.code.toLong()
            hash *= PRIME
        }
        // A separator, so "ab"+"c" and "a"+"bc" differ.
        hash = hash xor 0x1F
        return hash * PRIME
    }

    private const val OFFSET = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
    private const val PRIME = 0x100000001b3L
}

/**
 * Keeps the last index and rebuilds only when the corpus fingerprint, or the month (for
 * "this month" counts), changes. Nothing is stored beyond the process.
 */
class WordIndexCache(
    private val lemmatizer: Lemmatizer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private var cached: Pair<String, WordIndex>? = null

    /** How many times an index was built: for tests. */
    var builds = 0
        private set

    @Synchronized
    fun index(utterances: List<Utterance>): WordIndex {
        val now = clock()
        val key = CorpusFingerprint.of(utterances) + "@" + YearMonth.from(Instant.ofEpochMilli(now).atZone(zone()))
        cached?.let { (cachedKey, index) -> if (cachedKey == key) return index }
        val index = WordIndex.build(utterances, lemmatizer, now, zone())
        builds++
        cached = key to index
        return index
    }
}
