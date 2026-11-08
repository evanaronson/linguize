package com.evanaronson.linguize.core.vocab

import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.corpus.Utterance
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Where the writer used a word: characters `[start, end)` of an utterance. */
@Serializable
data class Example(val utterance: String, val start: Int, val end: Int, val at: Long)

/**
 * How often the writer used one deck row, when last, and the latest few places.
 * [bySpelling] is true when some use was matched by spelling only, without a part of speech.
 */
@Serializable
data class WordUse(val count: Int, val lastAt: Long, val examples: List<Example>, val bySpelling: Boolean = false)

/** Of the deck's [top] most frequent rows, how many the writer [used]. */
@Serializable
data class Milestone(val top: Int, val used: Int) {
    val percent: Int get() = if (top == 0) 0 else used * 100 / top
}

/**
 * One calendar month ("2026-11"). [topUsed] counts the deck's top rows used by the end of
 * that month (all time so far); [distinct] and [rarer] count lemmas used in that month only.
 */
@Serializable
data class MonthPoint(val month: String, val topUsed: Int, val distinct: Int, val rarer: Int, val utterances: Int) {
    val rarerPercent: Int get() = if (distinct == 0) 0 else rarer * 100 / distinct
}

/**
 * Lab, Vocabulary gaps: which rows of one deck the writer has used, built only from their
 * own input ([Utterance]), skipping the spans an accepted fix replaced. Derived data: it names
 * the [fingerprint] of the evidence it came from and is rebuilt when that changes.
 */
@Serializable
data class VocabReport(
    val fingerprint: String,
    val deckId: String,
    val language: String,
    val level: VocabLevel = VocabLevel.Lexeme,
    /** Utterances in the deck's language. */
    val utterances: Int,
    /** Words counted, outside corrected spans. */
    val words: Int,
    /** Words skipped because an accepted fix replaced them. */
    val tried: Int,
    /** Words that reached the deck by spelling only: no part of speech was known for them. */
    val bySpelling: Int = 0,
    /** [DeckEntry.key] to its use. Rows never used are absent. */
    val used: Map<String, WordUse>,
    val milestones: List<Milestone>,
    /** Distinct lemmas used, in the deck or not. */
    val distinct: Int,
    /** Of [distinct], those beyond rank [RARE_RANK] or not in the deck. */
    val rarer: Int,
    /** The size of the top group the trend follows: the top 1,000, or the whole deck if smaller. */
    val trendTop: Int,
    val trend: List<MonthPoint>,
) {
    /**
     * The milestone to lead with: the largest group the writer has used at least half of,
     * or the smallest one when they haven't reached half of any.
     */
    val headline: Milestone?
        get() = milestones.lastOrNull { it.used * 2 >= it.top } ?: milestones.firstOrNull()

    companion object {
        const val RARE_RANK = 2_000
        const val TREND_TOP = 1_000
        const val MAX_EXAMPLES = 5
        const val MAX_MONTHS = 24
    }
}

/**
 * Builds a [VocabReport]. Rules for one written word, in order:
 * 1. Lexemes [lemmatizer] knows, with their part of speech, count toward exactly those rows;
 *    the deck's own `forms` column maps a form to its row's exact lexeme.
 * 2. Otherwise the word is matched by spelling: the lemmas morphology gave (Identity gives the
 *    word itself) and a few irregular forms count toward every row with that lemma.
 * 3. Otherwise a small fallback folds plurals, gender and regular verb endings, and finally
 *    ignores missing accents, accepted only when the deck holds the result.
 * An ambiguous word counts toward every candidate it reaches at the first step that matches.
 */
class VocabAnalyser(private val lemmatizer: Lemmatizer, private val zone: ZoneId = ZoneId.systemDefault()) {

    fun analyse(deck: VocabDeck, corpus: List<Utterance>): VocabReport {
        val utterances = corpus.filter { it.language == deck.language }.sortedBy { it.at }
        val index = DeckIndex(deck, lemmatizer)
        val uses = HashMap<String, MutableList<Example>>()
        val spelled = HashSet<String>()
        val monthKeys = LinkedHashMap<YearMonth, MutableSet<String>>()
        val monthDeck = LinkedHashMap<YearMonth, MutableSet<String>>()
        val monthUtterances = HashMap<YearMonth, Int>()
        val allKeys = HashSet<String>()
        var words = 0
        var tried = 0
        var bySpelling = 0
        for (utterance in utterances) {
            val month = YearMonth.from(Instant.ofEpochMilli(utterance.at).atZone(zone))
            monthUtterances.merge(month, 1, Int::plus)
            val keys = monthKeys.getOrPut(month) { HashSet() }
            val inDeck = monthDeck.getOrPut(month) { HashSet() }
            fun use(entry: DeckEntry, start: Int, end: Int, spelling: Boolean) {
                uses.getOrPut(entry.key) { ArrayList() } += Example(utterance.id, start, end, utterance.at)
                if (spelling) spelled += entry.key
                inDeck += entry.key
                keys += DECK + entry.lemma
            }
            for (token in WordFolding.tokens(utterance.text)) {
                if (utterance.isCorrected(token.start, token.end)) {
                    tried++
                    continue
                }
                val word = token.text.lowercase()
                val match = index.match(word)
                if (match.entries.isNotEmpty()) {
                    words++
                    if (match.bySpelling) bySpelling++
                    match.entries.forEach { use(it, token.start, token.end, match.bySpelling) }
                    continue
                }
                // A capitalised word mid-sentence that no deck knows is most likely a name.
                if (word.length < 2 || (token.text.first().isUpperCase() && !token.sentenceStart)) continue
                words++
                keys += OTHER + (lemmatizer.lemmas(word, deck.language).minOrNull() ?: word)
            }
            for ((entry, regex) in index.phrases) {
                regex.findAll(utterance.text).forEach { found ->
                    val end = found.range.last + 1
                    if (!utterance.isCorrected(found.range.first, end)) use(entry, found.range.first, end, false)
                }
            }
            allKeys += keys
        }

        val used = uses.mapValues { (key, examples) ->
            WordUse(
                count = examples.size,
                lastAt = examples.maxOf { it.at },
                examples = examples.sortedByDescending { it.at }.take(VocabReport.MAX_EXAMPLES),
                bySpelling = key in spelled,
            )
        }
        val trendTop = minOf(VocabReport.TREND_TOP, deck.size)
        return VocabReport(
            fingerprint = fingerprint(deck, corpus, lemmatizer),
            deckId = deck.id,
            language = deck.language,
            level = deck.level,
            utterances = utterances.size,
            words = words,
            tried = tried,
            bySpelling = bySpelling,
            used = used,
            milestones = milestones(deck.size).map { top -> Milestone(top, deck.entries.take(top).count { it.key in used }) },
            distinct = allKeys.size,
            rarer = allKeys.count { index.isRare(it) },
            trendTop = trendTop,
            trend = trend(monthKeys, monthDeck, monthUtterances, index, trendTop),
        )
    }

    private fun trend(
        monthKeys: Map<YearMonth, Set<String>>,
        monthDeck: Map<YearMonth, Set<String>>,
        monthUtterances: Map<YearMonth, Int>,
        index: DeckIndex,
        trendTop: Int,
    ): List<MonthPoint> {
        val first = monthKeys.keys.minOrNull() ?: return emptyList()
        val last = monthKeys.keys.max()
        val seenTop = HashSet<String>()
        val points = ArrayList<MonthPoint>()
        var month = first
        while (month <= last) {
            monthDeck[month].orEmpty().filterTo(seenTop) { index.rank(it) <= trendTop }
            val keys = monthKeys[month].orEmpty()
            points += MonthPoint(month.toString(), seenTop.size, keys.size, keys.count { index.isRare(it) }, monthUtterances[month] ?: 0)
            month = month.plusMonths(1)
        }
        return points.takeLast(VocabReport.MAX_MONTHS)
    }

    companion object {
        /** Bump when the counting rules change, so saved reports are rebuilt. */
        const val VERSION = 2
        private const val DECK = "d:"
        private const val OTHER = "x:"

        /** 100, 500, 1,000, 2,000 and 5,000, each only when the deck is larger, then the whole deck. */
        fun milestones(size: Int): List<Int> =
            (listOf(100, 500, 1_000, 2_000, 5_000).filter { it < size } + size).filter { it > 0 }

        /**
         * Identifies the evidence a report is built from: the deck, the analyser and morphology,
         * and every utterance in the deck's language as it is now. Any deletion or edit changes it.
         */
        fun fingerprint(deck: VocabDeck, corpus: List<Utterance>, lemmatizer: Lemmatizer): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(value: String) {
                digest.update(value.toByteArray())
                digest.update(0)
            }
            add("v$VERSION")
            add(lemmatizer.javaClass.name)
            add(deck.id)
            add(deck.language)
            add(deck.level.name)
            deck.entries.forEach { add("${it.rank}:${it.key}:${it.forms.joinToString(";")}") }
            corpus.filter { it.language == deck.language }.sortedBy { it.id }.forEach { utterance ->
                add(utterance.id)
                add(utterance.at.toString())
                add(utterance.text)
                add(utterance.corrected.joinToString(",") { "${it.start}-${it.end}" })
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** Fast lookup of a deck's rows, and the matching rules for one written word. */
internal class DeckIndex(private val deck: VocabDeck, private val lemmatizer: Lemmatizer) {
    /** The deck rows a word reached; [bySpelling] when no part of speech backed the match. */
    data class Match(val entries: Set<DeckEntry>, val bySpelling: Boolean)

    private val language = deck.language
    private val words = deck.entries.filterNot { it.isPhrase }
    private val byKey: Map<String, DeckEntry> = words.associateBy { it.key }
    private val byLemma: Map<String, List<DeckEntry>> = words.groupBy { it.lemma }
    private val byForm: Map<String, List<DeckEntry>> = HashMap<String, MutableList<DeckEntry>>().also { map ->
        words.forEach { entry -> entry.forms.forEach { form -> map.getOrPut(form) { ArrayList() } += entry } }
    }
    private val byStripped: Map<String, List<DeckEntry>> = byLemma.keys
        .groupBy { WordFolding.stripAccents(it) }
        .filterValues { it.size == 1 }
        .mapValues { byLemma.getValue(it.value.single()) }
    val phrases: List<Pair<DeckEntry, Regex>> = deck.entries.filter { it.isPhrase }.map { entry ->
        entry to Regex("(?<!\\p{L})" + entry.lemma.split(' ').joinToString("\\s+") { Regex.escape(it) } + "(?!\\p{L})",
            setOf(RegexOption.IGNORE_CASE))
    }
    private val ranks: Map<String, Int> = deck.entries.associate { it.key to it.rank }
    private val lemmaRanks: Map<String, Int> = deck.entries.groupBy { it.lemma }.mapValues { (_, rows) -> rows.minOf { it.rank } }

    /** Spelling matches only matter where rows carry parts of speech. */
    private val spellingCounts = deck.level == VocabLevel.Lexeme && deck.hasLexemes

    fun rank(key: String): Int = ranks[key] ?: Int.MAX_VALUE

    /** A lemma key ("d:lemma" in the deck, "x:lemma" not) counts as rarer. */
    fun isRare(key: String): Boolean =
        !key.startsWith("d:") || (lemmaRanks[key.removePrefix("d:")] ?: Int.MAX_VALUE) > VocabReport.RARE_RANK

    /** The deck rows [word] (lowercase) counts toward. */
    fun match(word: String): Match {
        val lexemes = lemmatizer.lexemes(word, language)
        val exact = LinkedHashSet<DeckEntry>()
        for (lexeme in lexemes) {
            if (lexeme.pos == Pos.Unknown) continue
            byKey["${lexeme.lemma}/${lexeme.pos.token}"]?.let(exact::add)
            byLemma[lexeme.lemma]?.filterTo(exact) { it.pos == null }
        }
        byForm[word]?.let(exact::addAll)
        if (exact.isNotEmpty()) return Match(exact, false)
        if (deck.formsOnly) return Match(byLemma[word].orEmpty().toSet(), false)

        val spelling = (lexemes.map { it.lemma } + word + WordFolding.irregular(word, language))
            .flatMapTo(LinkedHashSet()) { byLemma[it].orEmpty() }
        if (spelling.isNotEmpty()) return Match(spelling, spellingCounts)
        val nominal = WordFolding.nominal(word, language)
        nominal.firstNotNullOfOrNull { byLemma[it] }?.let { return Match(it.toSet(), spellingCounts) }
        WordFolding.verbal(word, language).firstNotNullOfOrNull { candidate ->
            byLemma[candidate]?.filter { it.pos == Pos.Verb.token || it.pos == null && candidate.length > 3 }?.takeIf { it.isNotEmpty() }
        }?.let { return Match(it.toSet(), spellingCounts) }
        // Accents left off ("aqui", "telefono"): only when exactly one deck lemma fits.
        (listOf(word) + nominal).firstNotNullOfOrNull { byStripped[WordFolding.stripAccents(it)] }
            ?.let { return Match(it.toSet(), spellingCounts) }
        return Match(emptySet(), false)
    }
}
