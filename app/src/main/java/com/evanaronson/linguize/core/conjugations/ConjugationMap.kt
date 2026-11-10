package com.evanaronson.linguize.core.conjugations

import com.evanaronson.linguize.core.Sentences
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.corpus.WriterGaps
import com.evanaronson.linguize.core.morphology.Analysis
import com.evanaronson.linguize.core.morphology.Catalan
import com.evanaronson.linguize.core.morphology.FunctionWords
import com.evanaronson.linguize.core.morphology.Morphology
import com.evanaronson.linguize.core.morphology.Person
import com.evanaronson.linguize.core.morphology.Tense
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Lab: one cell of the grid: a tense and a person, or a non-finite form (person null). */
data class Cell(val tense: Tense, val person: Person?) {
    /** For saved navigation state: "present:1s", "gerund". */
    val token: String get() = if (person == null) tense.token else "${tense.token}:${person.token}"

    companion object {
        fun read(token: String): Cell? {
            val parts = token.split(':')
            val tense = Tense.read(parts[0]) ?: return null
            val person = parts.getOrNull(1)?.let { Person.read(it) ?: return null }
            return Cell(tense, person)
        }
    }
}

/** A sentence the writer typed, with the verb form found in it. */
data class Example(val utteranceId: String, val sentence: String, val form: String, val at: Long)

/**
 * What the writer did in one cell. [weight] is the number of uses, an ambiguous use counting a
 * share (see [ConjugationMap]); [sure] counts uses that could only be this cell. [verbs] maps
 * each verb (infinitive) to its weight here.
 */
data class CellStats(
    val cell: Cell,
    val weight: Double,
    val sure: Int,
    val verbs: Map<String, Double>,
    val lastUsed: Long,
    val examples: List<Example>,
)

/** One verb the writer used, the tenses it appeared in, and how often. */
data class VerbStats(val lemma: String, val weight: Double, val tenses: Set<Tense>, val cells: Set<Cell>, val lastUsed: Long)

/** A verb form Linguize flagged as wrong: tried, but not counted as known. */
data class Tried(val form: String, val readings: List<Reading>, val example: Example)

/** One candidate reading of a use: the verb and the cell. */
data class Reading(val lemma: String, val cell: Cell)

/** Distinct cells used in one calendar month, and all cells used up to its end. */
data class MonthPoint(val month: YearMonth, val cells: Int, val cumulative: Int)

data class ConjugationReport(
    val language: String,
    val cells: Map<Cell, CellStats>,
    val verbs: List<VerbStats>,
    val tried: List<Tried>,
    val trend: List<MonthPoint>,
    /** Utterances in [language] that were read. */
    val utterances: Int,
) {
    val rows: List<Tense> get() = ConjugationMap.rows(language)

    fun stats(cell: Cell): CellStats? = cells[cell]

    /** Finite cells that exist in this language's grid. */
    val gridCells: List<Cell> get() = rows.flatMap { tense -> Person.entries.filter { ConjugationMap.applies(tense, it) }.map { Cell(tense, it) } }

    val usedGridCells: Int get() = gridCells.count { cells[it] != null }

    val isEmpty: Boolean get() = cells.isEmpty() && tried.isEmpty()
}

/**
 * Lab: counts which verb forms the writer used correctly, from their own input only
 * (docs/lab/corpus.md). Rules:
 *
 * - Only utterances in the requested language are read. A word inside a corrected span is not
 *   counted; it is listed under [ConjugationReport.tried].
 * - Compound tenses are read across words: a finite form of haber (Spanish) or haver (Catalan),
 *   optionally followed by one adverb (ya, ja, siempre…), then a participle. The pair counts once,
 *   for the participle's verb, in the tense given by the auxiliary (he → perfect, había →
 *   pluperfect…) and the auxiliary's person. Catalan "vaig/vas/va/vam/vau/van" (and vares,
 *   vàrem…) followed by an infinitive is the periphrastic past of that infinitive's verb. If
 *   either word of a pair is corrected, the pair is tried, not counted.
 * - After an article, determiner or preposition ("el trabajo", "de paso") only an infinitive
 *   reading is kept: a finite form there is a noun. Words that are verbs on paper but nearly
 *   always something else ("como", "para") are not counted.
 * - Ambiguity: every use counts 1 in total, shared equally between its distinct readings
 *   (verb, tense, person). "cante" (present subjunctive, 1st or 3rd person) adds ½ to each
 *   cell; "fui" (ir or ser, preterite 1st person) adds 1 to one cell, ½ to each verb. A cell
 *   counts as used when anything was added to it.
 * - Guessed readings (verbs not in the lexicon) count only as finite forms.
 */
object ConjugationMap {
    private const val EXAMPLES_PER_CELL = 3
    private const val SENTENCE_LIMIT = 200

    fun rows(language: String): List<Tense> = when (language) {
        "ca" -> listOf(
            Tense.Present, Tense.PeriphrasticPast, Tense.Imperfect, Tense.Future, Tense.Conditional,
            Tense.PresentSubjunctive, Tense.ImperfectSubjunctive, Tense.Imperative,
            Tense.Perfect, Tense.Pluperfect, Tense.FuturePerfect, Tense.ConditionalPerfect,
            Tense.PerfectSubjunctive, Tense.PluperfectSubjunctive, Tense.Preterite,
        )
        else -> listOf(
            Tense.Present, Tense.Preterite, Tense.Imperfect, Tense.Future, Tense.Conditional,
            Tense.PresentSubjunctive, Tense.ImperfectSubjunctive, Tense.Imperative,
            Tense.Perfect, Tense.Pluperfect, Tense.FuturePerfect, Tense.ConditionalPerfect,
            Tense.PerfectSubjunctive, Tense.PluperfectSubjunctive,
        )
    }

    val NON_FINITE = listOf(Tense.Infinitive, Tense.Gerund, Tense.Participle)

    /** No first person singular imperative. */
    fun applies(tense: Tense, person: Person): Boolean = !(tense == Tense.Imperative && person == Person.S1)

    fun build(
        utterances: List<Utterance>,
        language: String,
        morphology: Morphology,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ConjugationReport {
        val counter = Counter(zone)
        var read = 0
        utterances.filter { it.language == language }.sortedBy { it.at }.forEach { utterance ->
            read++
            units(utterance, language, morphology).forEach { unit -> counter.add(utterance, unit) }
        }
        return counter.report(language, read)
    }

    /** A use of a verb: one word, or an auxiliary and its participle or infinitive. */
    internal data class Unit(val start: Int, val end: Int, val form: String, val readings: List<Reading>, val corrected: Boolean)

    internal data class Token(val start: Int, val end: Int, val text: String)

    private val SPANISH_WORD = Regex("[\\p{L}]+")
    private val CATALAN_WORD = Regex("[\\p{L}·]+(?:['’\\-][\\p{L}·]+)*")

    internal fun tokens(text: String, language: String): List<Token> {
        // Gaps the writer left in square brackets are not their words.
        val gaps = WriterGaps.bracketed(text)
        return (if (language == "ca") CATALAN_WORD else SPANISH_WORD).findAll(text)
            .filterNot { WriterGaps.inGap(gaps, it.range.first, it.range.last + 1) }
            .map { Token(it.range.first, it.range.last + 1, it.value.lowercase().replace('’', '\'')) }
            .toList()
    }

    private val ADVERBS_BETWEEN = setOf("ya", "siempre", "nunca", "también", "todavía", "ja", "sempre", "mai", "també", "encara", "bien", "bé")

    internal fun units(utterance: Utterance, language: String, morphology: Morphology): List<Unit> {
        val tokens = tokens(utterance.text, language)
        val nominal = FunctionWords.nominalContext(language)
        val notVerbs = FunctionWords.notVerbs(language)
        val verbReadings: List<Set<Analysis>> = tokens.mapIndexed { index, token ->
            if (token.text in notVerbs) return@mapIndexed emptySet()
            val all = morphology.verbs(token.text, language)
                .filter { !it.guessed || it.tense?.finite == true }
            val previous = tokens.getOrNull(index - 1)?.takeIf { utterance.text.substring(it.end, token.start).isBlank() }
            val afterNominal = previous != null && lastPart(previous.text) in nominal
            (if (afterNominal) all.filter { it.tense == Tense.Infinitive } else all).toSet()
        }
        val auxiliary = if (language == "ca") "haver" else "haber"
        val units = mutableListOf<Unit>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            val readings = verbReadings[index]
            val compound = compound(index, tokens, verbReadings, auxiliary, language)
            if (compound != null) {
                val (last, found) = compound
                val start = token.start
                val end = tokens[last].end
                units += Unit(start, end, utterance.text.substring(start, end), found, utterance.isCorrected(start, end))
                index = last + 1
                continue
            }
            val found = readings.mapNotNull { analysis ->
                val tense = analysis.tense ?: return@mapNotNull null
                // A periphrastic auxiliary without its infinitive is not a use of anything.
                if (tense == Tense.PeriphrasticPast) return@mapNotNull null
                Reading(analysis.lemma, Cell(tense, if (tense.finite) analysis.person else null))
            }.distinct()
            if (found.isNotEmpty()) {
                units += Unit(token.start, token.end, utterance.text.substring(token.start, token.end), found, utterance.isCorrected(token.start, token.end))
            }
            index++
        }
        return units
    }

    private fun lastPart(word: String): String = word.split('\'', '-').last { it.isNotEmpty() }

    /** The index of the compound's last word and its readings, or null. */
    private fun compound(
        index: Int,
        tokens: List<Token>,
        readings: List<Set<Analysis>>,
        auxiliary: String,
        language: String,
    ): Pair<Int, List<Reading>>? {
        val here = readings[index]
        var next = index + 1
        if (next < tokens.size && tokens[next].text in ADVERBS_BETWEEN) next++
        if (next >= tokens.size) return null
        val following = readings[next]

        val perfect = here.filter { it.lemma == auxiliary && it.tense != null && it.tense.finite && it.person != null && COMPOUND[it.tense] != null }
        if (perfect.isNotEmpty()) {
            val participles = following.filter { it.tense == Tense.Participle }.map { it.lemma }.distinct()
            if (participles.isNotEmpty()) {
                return next to perfect.flatMap { aux ->
                    participles.map { Reading(it, Cell(COMPOUND.getValue(aux.tense!!), aux.person)) }
                }.distinct()
            }
        }
        if (language == "ca" && next == index + 1) {
            val person = lastPart(tokens[index].text).let { Catalan.PERIPHRASTIC[it] }
            val isAuxiliary = here.any { it.lemma == "anar" && (it.tense == Tense.Present || it.tense == Tense.PeriphrasticPast) }
            if (person != null && isAuxiliary) {
                val infinitives = following.filter { it.tense == Tense.Infinitive }.map { it.lemma }.distinct()
                if (infinitives.isNotEmpty()) return next to infinitives.map { Reading(it, Cell(Tense.PeriphrasticPast, person)) }
            }
        }
        return null
    }

    private val COMPOUND = mapOf(
        Tense.Present to Tense.Perfect,
        Tense.Imperfect to Tense.Pluperfect,
        Tense.Preterite to Tense.Pluperfect,
        Tense.Future to Tense.FuturePerfect,
        Tense.Conditional to Tense.ConditionalPerfect,
        Tense.PresentSubjunctive to Tense.PerfectSubjunctive,
        Tense.ImperfectSubjunctive to Tense.PluperfectSubjunctive,
    )

    private class Counter(private val zone: ZoneId) {
        class MutableCell(val cell: Cell) {
            var weight = 0.0
            var sure = 0
            val verbs = LinkedHashMap<String, Double>()
            var lastUsed = 0L
            val examples = ArrayDeque<Example>()
        }

        class MutableVerb(val lemma: String) {
            var weight = 0.0
            val cells = LinkedHashSet<Cell>()
            var lastUsed = 0L
        }

        val cells = LinkedHashMap<Cell, MutableCell>()
        val verbs = LinkedHashMap<String, MutableVerb>()
        val tried = mutableListOf<Tried>()
        val months = sortedMapOf<YearMonth, MutableSet<Cell>>()

        fun add(utterance: Utterance, unit: Unit) {
            val example = Example(utterance.id, sentence(utterance.text, unit.start, unit.end), unit.form, utterance.at)
            if (unit.corrected) {
                tried += Tried(unit.form, unit.readings, example)
                return
            }
            val share = 1.0 / unit.readings.size
            val sure = unit.readings.map { it.cell }.distinct().size == 1
            val month = YearMonth.from(Instant.ofEpochMilli(utterance.at).atZone(zone))
            unit.readings.forEach { reading ->
                val cell = cells.getOrPut(reading.cell) { MutableCell(reading.cell) }
                cell.weight += share
                cell.verbs.merge(reading.lemma, share, Double::plus)
                cell.lastUsed = maxOf(cell.lastUsed, utterance.at)
                val verb = verbs.getOrPut(reading.lemma) { MutableVerb(reading.lemma) }
                verb.weight += share
                verb.cells += reading.cell
                verb.lastUsed = maxOf(verb.lastUsed, utterance.at)
                if (reading.cell.person != null) months.getOrPut(month) { mutableSetOf() } += reading.cell
            }
            unit.readings.map { it.cell }.distinct().forEach { key ->
                val cell = cells.getValue(key)
                if (sure) cell.sure++
                if (cell.examples.none { it.sentence == example.sentence }) {
                    cell.examples.addFirst(example)
                    if (cell.examples.size > EXAMPLES_PER_CELL) cell.examples.removeLast()
                }
            }
        }

        fun report(language: String, read: Int): ConjugationReport {
            val trend = mutableListOf<MonthPoint>()
            if (months.isNotEmpty()) {
                val seen = mutableSetOf<Cell>()
                var month = months.firstKey()
                val last = months.lastKey()
                while (month <= last) {
                    val used = months[month].orEmpty()
                    seen += used
                    trend += MonthPoint(month, used.size, seen.size)
                    month = month.plusMonths(1)
                }
            }
            return ConjugationReport(
                language = language,
                cells = cells.mapValues { (_, c) -> CellStats(c.cell, c.weight, c.sure, c.verbs.toMap(), c.lastUsed, c.examples.toList()) },
                verbs = verbs.values.map { v -> VerbStats(v.lemma, v.weight, v.cells.mapTo(linkedSetOf()) { it.tense }, v.cells.toSet(), v.lastUsed) }
                    .sortedWith(compareByDescending<VerbStats> { it.weight }.thenBy { it.lemma }),
                tried = tried.sortedByDescending { it.example.at },
                trend = trend,
                utterances = read,
            )
        }
    }

    /** The sentence around `[start, end)`, cut to about [SENTENCE_LIMIT] characters around the form. */
    internal fun sentence(text: String, start: Int, end: Int): String {
        val range = Sentences.ranges(text).firstOrNull { start in it } ?: text.indices
        var from = range.first
        var to = minOf(range.last + 1, text.length)
        if (to - from > SENTENCE_LIMIT) {
            from = maxOf(from, start - SENTENCE_LIMIT / 2)
            to = minOf(to, maxOf(end, from + SENTENCE_LIMIT))
        }
        val cut = text.substring(from, to).trim()
        return (if (from > range.first) "…" else "") + cut + (if (to < range.last + 1) "…" else "")
    }
}
