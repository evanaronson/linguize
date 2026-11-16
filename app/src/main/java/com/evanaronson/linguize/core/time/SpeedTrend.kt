package com.evanaronson.linguize.core.time

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One point of [SpeedTrend]: a week, or two weeks when most weeks are thin. Its figures are null
 * when it holds too little to say anything (a thin period is shown as such, never plotted).
 */
data class SpeedPoint(
    /** The Monday the period starts on. */
    val start: LocalDate,
    /** Measured chat messages of at least [WritingEstimator.MIN_WORDS_PER_MESSAGE] words in it. */
    val messages: Int,
    val words: Int,
    /** Writing, ms per word (word-weighted median); null when the period is thin. */
    val msPerWord: Long?,
    /** Messages whose card offered at least one change. */
    val reviewMessages: Int,
    /** Review, ms per change offered (change-weighted median); null when too few cards offered one. */
    val msPerChange: Long?,
)

enum class SpeedDirection { Faster, Slower, Same }

/**
 * The writing speed of the latest period with enough data against the earliest one, [weeksAgo]
 * weeks before it. [percent] is how much less (Faster) or more (Slower) time a word takes,
 * rounded to 5; 0 for Same.
 */
data class SpeedChange(val direction: SpeedDirection, val percent: Int, val weeksAgo: Int)

/**
 * Lab, Time spent: are you getting faster or slower at writing in one language? Pure.
 *
 * - **What counts.** Writing: measured chat messages only (writing in other apps is estimated
 *   *from* chat speed, so it can't say anything about it), of at least
 *   [WritingEstimator.MIN_WORDS_PER_MESSAGE] words (one- and two-word replies are mostly
 *   reaction time), recorded under rules version [WRITING_RULES] or later: rows recorded with
 *   the old 60 s pause cap can't be re-capped, so they're left out rather than mixed in
 *   ([leftOut] counts them). Review: every message whose card offered a change, from any
 *   source and any rules version (review's rules haven't changed).
 * - **One language.** [language] is the scope, or under Linguize (null scope) the language
 *   with the most such words in the window ([dominant]): one line mixing languages would show
 *   a change of language as a change of speed. Records with no language never count.
 * - **Periods.** The last [WEEKS] calendar weeks, Monday first. Weekly when at least half the
 *   weeks with any chat writing have enough for a point; otherwise two weeks per point, ending
 *   with this week. A point needs [WritingEstimator.MIN_MESSAGES] messages and
 *   [WritingEstimator.MIN_WORDS] words (writing), or [MIN_REVIEW_MESSAGES] cards with a change
 *   (review).
 * - **Figures** are the same medians as the page's averages ([Median.rate]), so one slow
 *   message in a period barely moves its point.
 * - **The change** ([change]) compares the earliest and the latest writing points with enough
 *   data, only when the latest covers this week or last week and they start at least
 *   [MIN_WEEKS_APART] weeks apart. Within [SAME_BAND] (10%) either way it's "about the same":
 *   a median of a handful of messages moves that much week to week. Both points are always
 *   under the same writing rules, so it never compares across the cap change.
 */
data class SpeedTrend(
    /** The language shown; null when there's none to show (no measured chat writing in the window under Linguize). */
    val language: String?,
    /** True when [language] was picked as the most-written one, under Linguize. */
    val dominant: Boolean,
    /** Oldest first; empty when [language] is null. */
    val points: List<SpeedPoint>,
    /** 1, or 2 when most weeks are thin. */
    val weeksPerPoint: Int,
    /** Null when there isn't enough to say. */
    val change: SpeedChange?,
    /** Measured chat messages in this language and window recorded under older writing rules, left out. */
    val leftOut: Int,
) {
    val hasWriting: Boolean get() = points.any { it.msPerWord != null }
    val hasReview: Boolean get() = points.any { it.msPerChange != null }

    companion object {
        const val WEEKS = 8

        /**
         * The first [TimeRecord.rules] version whose writing time compares with today's: version 2
         * capped pauses at 30 s, not 60 s. Raise it whenever the writing rules change.
         */
        const val WRITING_RULES = 2

        const val MIN_REVIEW_MESSAGES = 5
        const val MIN_WEEKS_APART = 2

        /** Per mille: within 10% either way is "about the same". */
        const val SAME_BAND = 100

        fun of(records: List<TimeRecord>, now: Long, zone: ZoneId = ZoneId.systemDefault(), scope: String? = null): SpeedTrend {
            val thisMonday = mondayOf(now, zone)
            val firstMonday = thisMonday.minusWeeks((WEEKS - 1).toLong())
            val window = records.filter { record ->
                record.language != null && mondayOf(record.at, zone).let { !it.isBefore(firstMonday) && !it.isAfter(thisMonday) }
            }
            val language = scope ?: window.filter(::writes).groupBy { it.language!! }
                .maxWithOrNull(compareBy<Map.Entry<String, List<TimeRecord>>> { entry -> entry.value.sumOf { it.words } }
                    .thenByDescending { it.key })
                ?.key
                ?: return SpeedTrend(null, false, emptyList(), 1, null, 0)
            val mine = window.filter { it.language == language }
            val writing = mine.filter(::writes)
            val reviewed = mine.filter { it.changesShown > 0 }
            val leftOut = mine.count { it.writingMs != null && it.rules < WRITING_RULES && it.words >= WritingEstimator.MIN_WORDS_PER_MESSAGE }

            val weekly = (0 until WEEKS).map { firstMonday.plusWeeks(it.toLong()) }
            val weeksWithWriting = weekly.map { week -> writing.filter { mondayOf(it.at, zone) == week } }.filter { it.isNotEmpty() }
            val enoughWeekly = weeksWithWriting.count(::enoughWriting)
            val weeksPerPoint = if (enoughWeekly * 2 >= weeksWithWriting.size) 1 else 2
            val starts = (0 until WEEKS step weeksPerPoint).map { firstMonday.plusWeeks(it.toLong()) }
            val points = starts.map { start ->
                val end = start.plusWeeks(weeksPerPoint.toLong())
                fun inPeriod(record: TimeRecord) = mondayOf(record.at, zone).let { !it.isBefore(start) && it.isBefore(end) }
                val written = writing.filter(::inPeriod)
                val cards = reviewed.filter(::inPeriod)
                SpeedPoint(
                    start = start,
                    messages = written.size,
                    words = written.sumOf { it.words },
                    msPerWord = if (enoughWriting(written)) Median.rate(written, { it.writingMs!! }, { it.words }) else null,
                    reviewMessages = cards.size,
                    msPerChange = if (cards.size >= MIN_REVIEW_MESSAGES) Median.rate(cards, { it.reviewMs }, { it.changesShown }) else null,
                )
            }
            return SpeedTrend(language, scope == null, points, weeksPerPoint, change(points, weeksPerPoint, thisMonday), leftOut)
        }

        /** The latest point with enough writing against the earliest one, or null when that would claim too much. */
        internal fun change(points: List<SpeedPoint>, weeksPerPoint: Int, thisMonday: LocalDate): SpeedChange? {
            val enough = points.filter { it.msPerWord != null }
            if (enough.size < 2) return null
            val earliest = enough.first()
            val latest = enough.last()
            // "You're writing…" is about now: the latest point must reach this week or last week.
            if (latest.start.plusWeeks(weeksPerPoint.toLong()).isBefore(thisMonday)) return null
            val weeksAgo = ChronoUnit.WEEKS.between(earliest.start, latest.start).toInt()
            if (weeksAgo < MIN_WEEKS_APART) return null
            val before = earliest.msPerWord!!
            if (before <= 0L) return null
            val perMille = ((latest.msPerWord!! - before) * 1000.0 / before).roundToInt()
            return when {
                perMille <= -SAME_BAND -> SpeedChange(SpeedDirection.Faster, roundTo5(abs(perMille)), weeksAgo)
                perMille >= SAME_BAND -> SpeedChange(SpeedDirection.Slower, roundTo5(perMille), weeksAgo)
                else -> SpeedChange(SpeedDirection.Same, 0, weeksAgo)
            }
        }

        /** Per mille to a percent, to the nearest 5 (at least 10, the band's edge). */
        private fun roundTo5(perMille: Int): Int = (((perMille + 25) / 50) * 5).coerceAtLeast(SAME_BAND / 10)

        private fun writes(record: TimeRecord) =
            record.writingMs != null && record.rules >= WRITING_RULES && record.words >= WritingEstimator.MIN_WORDS_PER_MESSAGE

        private fun enoughWriting(records: List<TimeRecord>) =
            records.size >= WritingEstimator.MIN_MESSAGES && records.sumOf { it.words } >= WritingEstimator.MIN_WORDS

        private fun mondayOf(at: Long, zone: ZoneId): LocalDate =
            Instant.ofEpochMilli(at).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
}
