package com.evanaronson.linguize.core.chat

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/**
 * Lab, Translation reliance: one partner reply that can be measured (it arrived with its own
 * translation, after this was added), in [language], at [at]; [neededAt] is when its translation
 * was needed ([TranslationDwell]), null if it wasn't.
 */
data class ReplyRead(val messageId: String, val language: String, val at: Long, val neededAt: Long? = null) {
    val needed: Boolean get() = neededAt != null
}

/** One week, Monday first: its replies and how many needed a translation; [percent] only with enough replies. */
data class WeekShare(val start: LocalDate, val replies: Int, val needed: Int) {
    val percent: Int? get() = if (replies >= TranslationReliance.MIN_WEEK_REPLIES) percentOf(needed, replies) else null
}

/** Which way the last four weeks went, against the four before. */
enum class RelianceDirection { Down, Up, Same }

/** The last four weeks' share against the four weeks before, both with enough replies. */
data class RelianceTrend(val direction: RelianceDirection, val recentPercent: Int, val earlierPercent: Int)

/**
 * How often the writer needed a reply's translation, in one language (or all, null). Derived
 * on read from [ReplyRead]s, never stored.
 *
 * - **Summary**: needed of all measured replies; the percentage only from [MIN_SUMMARY_REPLIES].
 * - **Weeks**: the last [WEEKS] calendar weeks, Monday first, in the phone's time zone, by when
 *   the reply arrived. A week with fewer than [MIN_WEEK_REPLIES] replies has no point.
 * - **Trend**: the last 28 days against the 28 before, each with at least [MIN_TREND_REPLIES]
 *   replies. Within [SAME_POINTS] percentage points it's about the same.
 */
data class TranslationReliance(
    val replies: Int,
    val needed: Int,
    val weeks: List<WeekShare>,
    val trend: RelianceTrend?,
) {
    val isEmpty: Boolean get() = replies == 0

    /** The share as a whole percentage, once there are enough replies for one to mean something. */
    val percent: Int? get() = if (replies >= MIN_SUMMARY_REPLIES) percentOf(needed, replies) else null

    /** Whether any week has a point to draw. */
    val hasWeeklyPoints: Boolean get() = weeks.any { it.percent != null }

    companion object {
        const val WEEKS = 8
        const val MIN_SUMMARY_REPLIES = 10
        const val MIN_WEEK_REPLIES = 5
        const val MIN_TREND_REPLIES = 20
        const val SAME_POINTS = 10
        private const val WINDOW_DAYS = 28L
        private const val DAY_MS = 86_400_000L

        fun of(reads: List<ReplyRead>, now: Long, zone: ZoneId = ZoneId.systemDefault(), language: String? = null): TranslationReliance {
            val scoped = if (language == null) reads else reads.filter { it.language == language }
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val byWeek = scoped.groupBy { weekOf(it.at, zone) }
            val weeks = (WEEKS - 1 downTo 0).map { back ->
                val start = thisMonday.minusWeeks(back.toLong())
                val inWeek = byWeek[start].orEmpty()
                WeekShare(start, inWeek.size, inWeek.count { it.needed })
            }
            val window = WINDOW_DAYS * DAY_MS
            val recent = scoped.filter { it.at > now - window && it.at <= now }
            val earlier = scoped.filter { it.at > now - 2 * window && it.at <= now - window }
            return TranslationReliance(scoped.size, scoped.count { it.needed }, weeks, trend(recent, earlier))
        }

        internal fun trend(recent: List<ReplyRead>, earlier: List<ReplyRead>): RelianceTrend? {
            if (recent.size < MIN_TREND_REPLIES || earlier.size < MIN_TREND_REPLIES) return null
            val now = percentOf(recent.count { it.needed }, recent.size)
            val before = percentOf(earlier.count { it.needed }, earlier.size)
            val direction = when {
                now <= before - SAME_POINTS -> RelianceDirection.Down
                now >= before + SAME_POINTS -> RelianceDirection.Up
                else -> RelianceDirection.Same
            }
            return RelianceTrend(direction, now, before)
        }

        private fun weekOf(at: Long, zone: ZoneId): LocalDate =
            Instant.ofEpochMilli(at).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
}

internal fun percentOf(part: Int, whole: Int): Int = if (whole == 0) 0 else (part * 100.0 / whole).roundToInt()
