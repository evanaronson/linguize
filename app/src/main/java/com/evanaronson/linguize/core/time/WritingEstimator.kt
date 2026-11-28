package com.evanaronson.linguize.core.time

import kotlin.math.abs

/** What an estimate of writing time is based on, weakest last. */
enum class EstimateBasis {
    /** The writer's own measured speed in the message's language. */
    Language,

    /** The writer's own measured speed across languages: not enough in this one yet. */
    AllLanguages,

    /** A typical speed: not enough measured writing yet. */
    Default,
}

/** An estimated writing time: always labelled as estimated where it's shown. */
data class WritingEstimate(val ms: Long, val basis: EstimateBasis, val msPerWord: Long)

/**
 * Lab, Time spent: estimates how long a message took to write when its writing wasn't measured
 * (written in another app, or a check saved before Time spent existed), from the writer's own
 * measured chat writing speed *around the time it was written*. Pure; computed on read, nothing
 * stored.
 *
 * - **Which messages teach the speed.** Measured chat messages ([TimeRecord.writingMs]) of at
 *   least [MIN_WORDS_PER_MESSAGE] words: one- and two-word replies are mostly reaction time.
 * - **Speed at the time.** For a message written at `at`, the [NEAREST] such messages nearest
 *   to it in time (before or after, by [TimeRecord.at]; a tie goes to the earlier one), in its
 *   language, as the word-weighted median of their seconds per word ([Median.rate]): half their
 *   words were written at that speed or faster, so one message left open within the idle caps
 *   doesn't slow the estimate. With fewer than [NEAREST] in the language, all of them.
 * - **Thresholds.** The language needs [MIN_MESSAGES] such messages and [MIN_WORDS] words in
 *   all; otherwise every language pooled, chosen by the same nearest-[NEAREST] rule under the
 *   same thresholds; otherwise [DEFAULT_MS_PER_WORD]. A selection of [NEAREST] always passes
 *   them, so whether a language has enough doesn't depend on the time ([basis]).
 * - **Stability.** An estimate only moves when a measured message lands among its nearest
 *   [NEAREST]: new writing far away in time leaves old estimates as they were, unlike version 2,
 *   which used today's overall speed for every message.
 */
class WritingEstimator(measured: List<TimeRecord>) {
    private val usable = measured.filter { it.writingMs != null && it.words >= MIN_WORDS_PER_MESSAGE }
    private val pooled: List<TimeRecord>? = candidates(usable)
    private val byLanguage: Map<String, List<TimeRecord>> = usable.filter { it.language != null }.groupBy { it.language!! }
        .mapNotNull { (language, records) -> candidates(records)?.let { language to it } }.toMap()

    /** What a message in [language] is estimated from; the same at any time. */
    fun basis(language: String?): EstimateBasis = when {
        language != null && language in byLanguage -> EstimateBasis.Language
        pooled != null -> EstimateBasis.AllLanguages
        else -> EstimateBasis.Default
    }

    /** Milliseconds per word for a message in [language] written at [at], and what that's based on. */
    fun speed(language: String?, at: Long): Pair<Long, EstimateBasis> {
        val basis = basis(language)
        val from = when (basis) {
            EstimateBasis.Language -> byLanguage.getValue(language!!)
            EstimateBasis.AllLanguages -> pooled!!
            EstimateBasis.Default -> return DEFAULT_MS_PER_WORD to basis
        }
        return (Median.rate(nearest(from, at), { it.writingMs ?: 0L }, { it.words }) ?: DEFAULT_MS_PER_WORD) to basis
    }

    fun estimate(words: Int, language: String?, at: Long): WritingEstimate {
        val (perWord, basis) = speed(language, at)
        return WritingEstimate(perWord * words.coerceAtLeast(0), basis, perWord)
    }

    /** The writing time of [record]: measured when it was, else estimated. */
    fun writingOf(record: TimeRecord): Long = record.writingMs ?: estimate(record.words, record.language, record.at).ms

    /** The writing time of an untimed History message: always estimated. */
    fun writingOf(message: UntimedMessage): Long = estimate(message.words, message.language, message.at).ms

    /** [records] sorted by time when they're enough to learn a speed from; else null. */
    private fun candidates(records: List<TimeRecord>): List<TimeRecord>? =
        if (enough(records)) records.sortedWith(compareBy<TimeRecord> { it.at }.thenBy { it.sessionId }) else null

    companion object {
        /**
         * Bump when the rules above change; shown in the explanation. 2: the word-weighted median,
         * not the pooled mean. 3: the speed of the [NEAREST] measured messages nearest in time,
         * not the overall speed.
         */
        const val VERSION = 3
        const val NEAREST = 20
        const val MIN_MESSAGES = 5
        const val MIN_WORDS = 30
        const val MIN_WORDS_PER_MESSAGE = 3

        /** About 15 words a minute: a careful writer composing on a phone in a second language. */
        const val DEFAULT_MS_PER_WORD = 4_000L

        internal fun enough(records: List<TimeRecord>) = records.size >= MIN_MESSAGES && records.sumOf { it.words } >= MIN_WORDS

        /**
         * The [count] records of [sorted] (by time) nearest to [at], before or after; on equal
         * distance the earlier one first. All of them when there are no more than [count].
         */
        internal fun nearest(sorted: List<TimeRecord>, at: Long, count: Int = NEAREST): List<TimeRecord> {
            if (sorted.size <= count) return sorted
            // The first record at or after [at]; walk outwards from there.
            var after = sorted.binarySearchBy(at) { it.at }.let { if (it < 0) -it - 1 else it }
            while (after > 0 && sorted[after - 1].at == at) after--
            var before = after - 1
            val picked = ArrayList<TimeRecord>(count)
            while (picked.size < count) {
                val left = sorted.getOrNull(before)
                val right = sorted.getOrNull(after)
                if (right == null || (left != null && abs(at - left.at) <= abs(right.at - at))) {
                    picked += left!!
                    before--
                } else {
                    picked += right
                    after++
                }
            }
            return picked
        }
    }
}
