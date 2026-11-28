package com.evanaronson.linguize.core.time

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Time over some set of messages; writing split into measured and estimated. Review is measured
 * only: [reviewUntimedMessages] says how many messages it doesn't cover (older checks).
 */
data class TimeTotals(
    /** Every message counted: timed ones and untimed History messages. */
    val messages: Int = 0,
    val measuredWritingMs: Long = 0,
    val estimatedWritingMs: Long = 0,
    /** Measured review only; never estimated. */
    val reviewMs: Long = 0,
    /** Messages whose writing was estimated (other apps, and untimed History messages). */
    val estimatedMessages: Int = 0,
    /** Messages whose review wasn't timed ([UntimedMessage]): not in [reviewMs], never counted as zero in averages. */
    val reviewUntimedMessages: Int = 0,
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
        reviewUntimedMessages + other.reviewUntimedMessages,
    )
}

/** One week, Monday first. */
data class WeekTime(val start: LocalDate, val totals: TimeTotals)

data class LanguageTime(
    val language: String,
    val totals: TimeTotals,
    /** Measured chat writing speed in this language, ms per word; null until there's some. */
    val msPerWord: Long?,
    /** What an unmeasured message in this language is estimated with. */
    val estimateBasis: EstimateBasis,
)

/**
 * Lab, Time spent: everything the Time page shows, from the stored measurements and the History
 * messages that have none ([UntimedMessage]: writing estimated, review not timed). Pure.
 *
 * Totals are true sums: writing is measured plus estimated, review is measured only. The
 * averages are medians ([Median]), so one unusually slow message (left open within the idle
 * caps) barely moves them:
 * - **Time per message**: the median of each timed message's writing (measured or estimated)
 *   plus its measured review. Untimed messages are left out: their review wasn't timed, and
 *   counting it as zero would make them look quicker than they were.
 * - **Seconds per word**: each measured chat message's writing time per word, as the
 *   word-weighted median. Nothing estimated enters it.
 * - **Time per change**: each message's review time per change offered, as the change-weighted
 *   median, counting only messages whose card offered at least one change (a clean message has
 *   nothing to review per change). Measured only.
 * - **Weeks**: the last [WEEKS] calendar weeks, Monday first, in the phone's time zone.
 * - **Speed**: [SpeedTrend], measured chat writing only, in one language.
 */
data class TimeReport(
    val allTime: TimeTotals,
    val thisWeek: TimeTotals,
    val weeks: List<WeekTime>,
    val languages: List<LanguageTime>,
    /** Median writing plus review per timed message (untimed review is left out, not zero); null with none. */
    val msPerMessage: Long?,
    /** Measured chat writing, ms per word (word-weighted median); null with no measured words. */
    val msPerWord: Long?,
    /** Review time per change offered (change-weighted median); null when no card offered a change. */
    val msPerChange: Long?,
    /** Changes offered and accepted, for the explanation. */
    val changesShown: Int,
    val changesAccepted: Int,
    /** What an unmeasured message with no language is estimated with. */
    val defaultBasis: EstimateBasis,
    /** Writing and review speed by week, in one language ([SpeedTrend]). */
    val speed: SpeedTrend,
) {
    val isEmpty: Boolean get() = allTime.isEmpty

    companion object {
        const val WEEKS = 8

        /**
         * The report for [records] and the [untimed] History messages (any with a record in
         * [records] is ignored). With [language] (Lab, Language scope) it covers only that
         * language's messages; estimates still use the writer's measurements in every language
         * (the pooled fallback needs them), the same as one message's line ([MessageTime]).
         */
        fun of(
            records: List<TimeRecord>,
            now: Long,
            zone: ZoneId = ZoneId.systemDefault(),
            language: String? = null,
            untimed: List<UntimedMessage> = emptyList(),
        ): TimeReport {
            val estimator = WritingEstimator(records)
            val speed = SpeedTrend.of(records, now, zone, language)
            val recorded = records.mapTo(HashSet()) { it.sessionId }
            @Suppress("NAME_SHADOWING")
            val records = if (language == null) records else records.filter { it.language == language }
            @Suppress("NAME_SHADOWING")
            val untimed = untimed.filter { it.sessionId !in recorded && (language == null || it.language == language) }
            val timed = records.map { Entry(it.language, it.at, it.totals(estimator)) }
            val entries = timed + untimed.map { Entry(it.language, it.at, it.totals(estimator)) }
            fun totals(of: List<Entry>) = of.fold(TimeTotals()) { sum, entry -> sum + entry.totals }
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val byWeek = entries.groupBy { weekOf(it.at, zone) }
            val weeks = (WEEKS - 1 downTo 0).map { back ->
                val start = thisMonday.minusWeeks(back.toLong())
                WeekTime(start, totals(byWeek[start].orEmpty()))
            }
            val recordsByLanguage = records.groupBy { it.language }
            val languages = entries.filter { it.language != null }.groupBy { it.language!! }
                .map { (language, group) ->
                    LanguageTime(language, totals(group), msPerWord(recordsByLanguage[language].orEmpty()), estimator.basis(language))
                }
                .sortedByDescending { it.totals.totalMs }
            return TimeReport(
                allTime = totals(entries),
                thisWeek = totals(byWeek[thisMonday].orEmpty()),
                weeks = weeks,
                languages = languages,
                msPerMessage = Median.of(timed.map { it.totals.totalMs }),
                msPerWord = msPerWord(records),
                msPerChange = Median.rate(records, { it.reviewMs }, { it.changesShown }),
                changesShown = records.sumOf { it.changesShown },
                changesAccepted = records.sumOf { it.changesAccepted },
                defaultBasis = estimator.basis(null),
                speed = speed,
            )
        }

        /** Measured chat writing only: each message's ms per word, word-weighted median. */
        private fun msPerWord(records: List<TimeRecord>): Long? =
            Median.rate(records.filter { it.writingMs != null }, { it.writingMs!! }, { it.words })

        private fun weekOf(at: Long, zone: ZoneId): LocalDate =
            Instant.ofEpochMilli(at).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        /** One message's contribution, timed or not. */
        private class Entry(val language: String?, val at: Long, val totals: TimeTotals)

        private fun UntimedMessage.totals(estimator: WritingEstimator) = TimeTotals(
            messages = 1,
            estimatedWritingMs = estimator.writingOf(this),
            estimatedMessages = 1,
            reviewUntimedMessages = 1,
        )

        private fun TimeRecord.totals(estimator: WritingEstimator): TimeTotals {
            val estimated = if (writingMs == null) estimator.writingOf(this) else 0L
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
