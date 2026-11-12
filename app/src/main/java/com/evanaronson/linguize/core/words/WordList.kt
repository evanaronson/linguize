package com.evanaronson.linguize.core.words

import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.vocab.DeckEntry
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Which rows of the one word list show. */
enum class WordFilter(val token: String) {
    /** Every deck row and every word of the writer's. */
    All("all"),
    /** Used right at least once. */
    Used("used"),
    /** Deck rows not yet used right (tried ones included). */
    NotYet("not_yet"),
    /** Flagged as wrong at least once. Shown as "Used wrong"; the token is older. */
    Tried("tried"),
    ;

    companion object {
        fun read(token: String?): WordFilter = entries.firstOrNull { it.token == token } ?: All
    }
}

/** How the list is ordered. [Rank] needs a deck; rows outside it come last. */
enum class WordSort(val token: String) {
    Rank("rank"),
    MostUsed("most_used"),
    /**
     * Most used wrong first, then the lower share right, then the most recently used. A word
     * fixed 5 times comes before one fixed once, even at a higher share right: it's the bigger
     * habit. Words never used wrong follow.
     */
    MostMisused("most_misused"),
    Newest("newest"),
    Alphabetical("az"),
    ;

    companion object {
        fun read(token: String?): WordSort? = entries.firstOrNull { it.token == token }
    }
}

/**
 * One row of the Words list: a deck row, a word of the writer's, or both. [counts] is the
 * writer's use, null for a deck row not reached at all. [rank] is the deck rank, null when the
 * word is not in the list ([notInList]) or there is no deck.
 */
data class WordRow(
    /** "d:<deck key>" for a deck row, "w:<entry key>" for a word of the writer's outside the deck. */
    val key: String,
    val label: String,
    val pos: Pos?,
    val rank: Int?,
    val gloss: String?,
    val counts: WordCounts?,
    /** A deck is chosen and this word of the writer's isn't in it. */
    val notInList: Boolean,
    /** The deck row, when this is one. */
    val deckEntry: DeckEntry? = null,
) {
    val used: Boolean get() = counts?.known == true
    /** Every recorded use was flagged as wrong. */
    val tried: Boolean get() = counts != null && counts.tried > 0 && !counts.known
    /** Used wrong at least once. */
    val wrong: Boolean get() = (counts?.wrong ?: 0) > 0
}

/** The distinct words used by a day, and how many of them are in the deck's top group. */
data class CoveragePoint(val day: LocalDate, val words: Int, val top: Int)

object WordList {
    /**
     * The union of [coverage]'s deck rows and the writer's own entries at [level], each word once.
     * - Lexemes and lemmas: every deck row (with its use, if any), then the writer's entries
     *   none of whose forms reached the deck ("Not in list").
     * - Word forms: every form the writer typed, ranked by the best deck row it reached, then
     *   the deck rows no form reached at all ("Not yet").
     * Without a deck, just the writer's entries.
     */
    fun rows(words: LanguageWords, coverage: DeckCoverage?, level: WordLevel): List<WordRow> {
        if (coverage == null) return words.rows(level).map { own(it, null, notInList = false) }
        val deckRows = coverage.deck.entries.map { entry ->
            WordRow("d:${entry.key}", entry.lemma, entry.pos?.let(Pos::of)?.takeIf { it != Pos.Unknown }, entry.rank, entry.gloss,
                coverage.uses[entry.key], notInList = false, deckEntry = entry)
        }
        return when (level) {
            WordLevel.Forms -> {
                val forms = words.words.map { own(it, coverage.rankOfForm(it.form), notInList = it.form !in coverage.formRows) }
                // Deck rows no form reached: unused ones, and phrases ("a menudo") found only in the texts.
                forms + deckRows.filter { (it.counts as? DeckUse)?.forms.isNullOrEmpty() }
            }
            WordLevel.Lexemes -> deckRows + words.lexemes.filter { entry -> entry.forms.none { it in coverage.formRows } }
                .map { own(it, null, notInList = true) }
            WordLevel.Lemmas -> deckRows + words.lemmas.filter { entry -> entry.forms.none { it in coverage.formRows } }
                .map { own(it, null, notInList = true) }
        }
    }

    private fun own(entry: WordCounts, rank: Int?, notInList: Boolean) = WordRow(
        key = "w:${entry.key}",
        label = entry.label,
        pos = posOf(entry),
        rank = rank,
        gloss = null,
        counts = entry,
        notInList = notInList,
    )

    /** One part of speech when the entry has exactly one known; null otherwise. */
    fun posOf(entry: WordCounts): Pos? = when (entry) {
        is LexemeEntry -> entry.lexeme.pos
        is WordEntry -> entry.lexemes.map { it.pos }.distinct().singleOrNull()
        is LemmaEntry -> entry.lexemes.map { it.pos }.distinct().singleOrNull()
        else -> null
    }?.takeIf { it != Pos.Unknown }

    fun matches(row: WordRow, filter: WordFilter): Boolean = when (filter) {
        WordFilter.All -> true
        WordFilter.Used -> row.used
        WordFilter.NotYet -> !row.used && row.deckEntry != null
        WordFilter.Tried -> row.wrong
    }

    fun counts(rows: List<WordRow>): Map<WordFilter, Int> = WordFilter.entries.associateWith { filter -> rows.count { matches(it, filter) } }

    /**
     * [rows] under [filter], matching [query] (accent-insensitive, anywhere in the word) and
     * ordered by [sort]. A–Z follows [locale]'s collation, so ç sorts with c and l·l with l.
     */
    fun arrange(rows: List<WordRow>, filter: WordFilter, sort: WordSort, query: String = "", locale: Locale = Locale.ROOT): List<WordRow> {
        val needle = WordViews.fold(query.trim())
        val matching = rows.filter { matches(it, filter) && (needle.isEmpty() || WordViews.fold(it.label).contains(needle)) }
        val byLabel = compareBy<WordRow> { it.label }.thenBy { it.key }
        return when (sort) {
            WordSort.Rank -> matching.sortedWith(compareBy<WordRow> { it.rank ?: Int.MAX_VALUE }
                .thenByDescending { it.counts?.uses ?: 0 }.then(byLabel))
            WordSort.MostUsed -> matching.sortedWith(compareByDescending<WordRow> { it.counts?.uses ?: 0 }
                .thenByDescending { it.counts?.lastAt ?: Long.MIN_VALUE }.thenBy { it.rank ?: Int.MAX_VALUE }.then(byLabel))
            WordSort.MostMisused -> matching.sortedWith(compareByDescending<WordRow> { it.counts?.wrong ?: 0 }
                .thenBy { it.counts?.accuracy ?: Int.MAX_VALUE }
                .thenByDescending { it.counts?.lastAt ?: Long.MIN_VALUE }.thenBy { it.rank ?: Int.MAX_VALUE }.then(byLabel))
            WordSort.Newest -> matching.sortedWith(compareByDescending<WordRow> { it.counts?.firstAt ?: Long.MIN_VALUE }
                .thenBy { it.rank ?: Int.MAX_VALUE }.then(byLabel))
            WordSort.Alphabetical -> {
                val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
                matching.sortedWith { a, b -> collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.key.compareTo(b.key) }
            }
        }
    }

    /**
     * One point per day either line grew: the writer's distinct words at a level ([entries],
     * by the day each was first used right) and how many of [coverage]'s top group they had
     * used by then. The second line is a subset of the first, so both share one axis.
     */
    fun growth(entries: List<WordCounts>, coverage: DeckCoverage?, zone: ZoneId = ZoneId.systemDefault()): List<CoveragePoint> {
        fun day(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val words = entries.mapNotNull { it.firstRightAt }.map(::day).groupingBy { it }.eachCount()
        val top = coverage?.let { c ->
            c.uses.values.filter { it.entry.rank <= c.trendTop }.mapNotNull { it.firstRightAt }.map(::day).groupingBy { it }.eachCount()
        }.orEmpty()
        var wordTotal = 0
        var topTotal = 0
        return (words.keys + top.keys).sorted().map { day ->
            wordTotal += words[day] ?: 0
            topTotal += top[day] ?: 0
            CoveragePoint(day, wordTotal, topTotal)
        }
    }
}
