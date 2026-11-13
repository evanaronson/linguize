package com.evanaronson.linguize.core.time

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Time over some set of messages; writing split into measured and estimated. */
data class TimeTotals(
    val messages: Int = 0,
    val measuredWritingMs: Long = 0,
    val estimatedWritingMs: Long = 0,
    val reviewMs: Long = 0,
    /** Messages whose writing was estimated. */
    val estimatedMessages: Int = 0,
) {
    val writingMs: Long get() = measuredWritingMs + estimatedWritingMs
    val totalMs: Long get() = writingMs + reviewMs
    val measuredMs: Long get() = measuredWritingMs + reviewMs
    val isEmpty: Boolean get() = messages == 0

    operator fun plus(other: TimeTotals) = TimeTotals(
        messages + other.messages,
        measuredWritingMs + other.measuredWritingMs,
        estimatedWritingMs + other.estimatedWritingMs,
        reviewMs + other.reviewMs,
        estimatedMessages + other.estimatedMessages,
    )
}

/** One week, Monday first. */
data class WeekTime(val start: LocalDate, val totals: TimeTotals)

data class LanguageTime(
    val language: String,
    val totals: TimeTotals,
    /** Measured chat writing speed in this language, ms per word; null until there's some. */
    val msPerWord: Long?,
    /** What another app's message in this language is estimated with. */
    val estimateBasis: EstimateBasis,
)

/**
 * Lab, Time spent: everything the Time page shows, from the stored measurements. Pure.
 *
 * Totals are true sums. The averages are medians ([Median]), so one unusually slow message
 * (left open within the idle caps) barely moves them:
 * - **Time per message**: the median of each message's writing (measured or estimated) plus review.
 * - **Seconds per word**: each measured chat message's writing time per word, as the
 *   word-weighted median. Nothing estimated enters it.
 * - **Time per change**: each message's review time per change offered, as the change-weighted
 *   median, counting only messages whose card offered at least one change (a clean message has
 *   nothing to review per change).
 * - **Weeks**: the last [WEEKS] calendar weeks, Monday first, in the phone's time zone.
 */
data class TimeReport(
    val allTime: TimeTotals,
    val thisWeek: TimeTotals,
    val weeks: List<WeekTime>,
    val languages: List<LanguageTime>,
    /** Median writing plus review per message; null with no messages. */
    val msPerMessage: Long?,
    /** Measured chat writing, ms per word (word-weighted median); null with no measured words. */
    val msPerWord: Long?,
    /** Review time per change offered (change-weighted median); null when no card offered a change. */
    val msPerChange: Long?,
    /** Changes offered and accepted, for the explanation. */
    val changesShown: Int,
    val changesAccepted: Int,
    /** What a message from another app with no language is estimated with. */
    val defaultBasis: EstimateBasis,
) {
    val isEmpty: Boolean get() = allTime.isEmpty

    companion object {
        const val WEEKS = 8

        /**
         * The report for [records]. With [language] (Lab, Language scope) it covers only that
         * language's messages; estimates still use the writer's speed across all of them.
         */
        fun of(records: List<TimeRecord>, now: Long, zone: ZoneId = ZoneId.systemDefault(), language: String? = null): TimeReport {
            val estimator = WritingEstimator(records)
            @Suppress("NAME_SHADOWING")
            val records = if (language == null) records else records.filter { it.language == language }
            fun totals(of: List<TimeRecord>) = of.fold(TimeTotals()) { sum, record -> sum + record.totals(estimator) }
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val byWeek = records.groupBy { weekOf(it.at, zone) }
            val weeks = (WEEKS - 1 downTo 0).map { back ->
                val start = thisMonday.minusWeeks(back.toLong())
                WeekTime(start, totals(byWeek[start].orEmpty()))
            }
            val languages = records.filter { it.language != null }.groupBy { it.language!! }
                .map { (language, group) ->
                    LanguageTime(language, totals(group), msPerWord(group), estimator.speed(language).second)
                }
                .sortedByDescending { it.totals.totalMs }
            val shown = records.sumOf { it.changesShown }
            return TimeReport(
                allTime = totals(records),
                thisWeek = totals(byWeek[thisMonday].orEmpty()),
                weeks = weeks,
                languages = languages,
                msPerMessage = Median.of(records.map { it.totals(estimator).totalMs }),
                msPerWord = msPerWord(records),
                msPerChange = Median.rate(records, { it.reviewMs }, { it.changesShown }),
                changesShown = shown,
                changesAccepted = records.sumOf { it.changesAccepted },
                defaultBasis = estimator.speed(null).second,
            )
        }

        /** Measured chat writing only: each message's ms per word, word-weighted median. */
        private fun msPerWord(records: List<TimeRecord>): Long? =
            Median.rate(records.filter { it.writingMs != null }, { it.writingMs!! }, { it.words })

        private fun weekOf(at: Long, zone: ZoneId): LocalDate =
            Instant.ofEpochMilli(at).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        private fun TimeRecord.totals(estimator: WritingEstimator): TimeTotals {
            val estimated = if (writingMs == null) estimator.estimate(words, language).ms else 0L
            return TimeTotals(
                messages = 1,
                measuredWritingMs = writingMs ?: 0L,
                estimatedWritingMs = estimated,
                reviewMs = reviewMs,
                estimatedMessages = if (writingMs == null) 1 else 0,
            )
        }
    }
}
