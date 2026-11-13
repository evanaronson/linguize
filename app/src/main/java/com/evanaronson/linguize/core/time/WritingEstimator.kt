package com.evanaronson.linguize.core.time

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
 * Lab, Time spent: estimates how long a message written in another app took to write, from
 * the writer's own measured chat writing speed (per word, per language). Pure; computed on
 * read, so old estimates improve as more is measured.
 *
 * Speed is each message's measured writing time per word, from messages of at least
 * [MIN_WORDS_PER_MESSAGE] words (one-word replies are mostly reaction time), as the word-weighted
 * median ([Median.rate]): half the words were written at that speed or faster, so one message
 * left open within the idle caps doesn't slow every estimate. A language needs
 * [MIN_MESSAGES] such messages and [MIN_WORDS] words; otherwise all languages pooled, under the
 * same thresholds; otherwise [DEFAULT_MS_PER_WORD].
 */
class WritingEstimator(measured: List<TimeRecord>) {
    private val usable = measured.filter { it.writingMs != null && it.words >= MIN_WORDS_PER_MESSAGE }
    private val pooled: Long? = speed(usable)
    private val byLanguage: Map<String, Long> = usable.filter { it.language != null }.groupBy { it.language!! }
        .mapNotNull { (language, records) -> speed(records)?.let { language to it } }.toMap()

    /** Milliseconds per word for [language], and what that's based on. */
    fun speed(language: String?): Pair<Long, EstimateBasis> =
        language?.let(byLanguage::get)?.let { it to EstimateBasis.Language }
            ?: pooled?.let { it to EstimateBasis.AllLanguages }
            ?: (DEFAULT_MS_PER_WORD to EstimateBasis.Default)

    fun estimate(words: Int, language: String?): WritingEstimate {
        val (perWord, basis) = speed(language)
        return WritingEstimate(perWord * words.coerceAtLeast(0), basis, perWord)
    }

    /** The writing time of [record]: measured when it was, else estimated. */
    fun writingOf(record: TimeRecord): Long = record.writingMs ?: estimate(record.words, record.language).ms

    private fun speed(records: List<TimeRecord>): Long? {
        val words = records.sumOf { it.words }
        if (records.size < MIN_MESSAGES || words < MIN_WORDS) return null
        return Median.rate(records, { it.writingMs ?: 0L }, { it.words })
    }

    companion object {
        /** Bump when the rules above change; shown in the explanation. 2: the word-weighted median, not the pooled mean. */
        const val VERSION = 2
        const val MIN_MESSAGES = 5
        const val MIN_WORDS = 30
        const val MIN_WORDS_PER_MESSAGE = 3

        /** About 15 words a minute: a careful writer composing on a phone in a second language. */
        const val DEFAULT_MS_PER_WORD = 4_000L
    }
}
