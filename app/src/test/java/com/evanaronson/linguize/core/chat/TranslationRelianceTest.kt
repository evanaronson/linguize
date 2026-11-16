package com.evanaronson.linguize.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Lab, Translation reliance: the share, its weeks and its trend, from measured replies. */
class TranslationRelianceTest {
    private val zone = ZoneOffset.UTC
    private val day = 86_400_000L

    /** Wednesday 2026-11-11, noon UTC. */
    private val now = LocalDate.of(2026, 11, 11).atTime(12, 0).toInstant(zone).toEpochMilli()

    private var next = 0
    private fun reads(count: Int, needed: Int, at: Long, language: String = "es") = List(count) { index ->
        ReplyRead("m${next++}", language, at, if (index < needed) at + 1_000 else null)
    }

    @Test fun nothingMeasuredIsEmpty() {
        val report = TranslationReliance.of(emptyList(), now, zone, "es")
        assertTrue(report.isEmpty)
        assertNull(report.percent)
        assertNull(report.trend)
        assertFalse(report.hasWeeklyPoints)
        assertEquals(TranslationReliance.WEEKS, report.weeks.size)
    }

    @Test fun shareCountsNeededOfAllReplies() {
        val report = TranslationReliance.of(reads(40, 12, now - day), now, zone, "es")
        assertEquals(40, report.replies)
        assertEquals(12, report.needed)
        assertEquals(30, report.percent)
    }

    @Test fun thePercentageWaitsForEnoughReplies() {
        val few = TranslationReliance.of(reads(TranslationReliance.MIN_SUMMARY_REPLIES - 1, 3, now), now, zone)
        assertEquals(9, few.replies)
        assertEquals(3, few.needed)
        assertNull(few.percent)
        val enough = TranslationReliance.of(reads(TranslationReliance.MIN_SUMMARY_REPLIES, 3, now), now, zone)
        assertEquals(30, enough.percent)
    }

    @Test fun onlyTheChosenLanguageCounts() {
        val all = reads(10, 5, now, "es") + reads(10, 0, now, "ca")
        assertEquals(50, TranslationReliance.of(all, now, zone, "es").percent)
        assertEquals(0, TranslationReliance.of(all, now, zone, "ca").percent)
        assertEquals(25, TranslationReliance.of(all, now, zone, null).percent)
        assertTrue(TranslationReliance.of(all, now, zone, "fr").isEmpty)
    }

    @Test fun weeksAreMondayFirstAndNeedEnoughRepliesForAPoint() {
        val monday = LocalDate.of(2026, 11, 9)
        val thisWeek = reads(5, 1, now)
        // Sunday of last week: a separate week.
        val lastWeek = reads(TranslationReliance.MIN_WEEK_REPLIES - 1, 4, now - 3 * day)
        val report = TranslationReliance.of(thisWeek + lastWeek, now, zone)
        assertEquals(monday, report.weeks.last().start)
        assertEquals(monday.minusWeeks(7), report.weeks.first().start)
        assertEquals(WeekShare(monday, 5, 1), report.weeks.last())
        assertEquals(20, report.weeks.last().percent)
        assertEquals(4, report.weeks[6].replies)
        assertNull(report.weeks[6].percent)
        assertTrue(report.hasWeeklyPoints)
        // A week where nothing was needed is a real point, 0%.
        assertEquals(0, WeekShare(monday, 6, 0).percent)
    }

    @Test fun repliesOlderThanEightWeeksCountInTheTotalButNoWeek() {
        val report = TranslationReliance.of(reads(10, 10, now - 70 * day), now, zone)
        assertEquals(10, report.replies)
        assertFalse(report.hasWeeklyPoints)
    }

    @Test fun trendComparesTheLastFourWeeksWithTheFourBefore() {
        val down = TranslationReliance.of(reads(20, 9, now - 40 * day) + reads(20, 4, now - 5 * day), now, zone)
        assertEquals(RelianceTrend(RelianceDirection.Down, 20, 45), down.trend)
        val up = TranslationReliance.of(reads(20, 4, now - 40 * day) + reads(20, 9, now - 5 * day), now, zone)
        assertEquals(RelianceTrend(RelianceDirection.Up, 45, 20), up.trend)
        val same = TranslationReliance.of(reads(20, 6, now - 40 * day) + reads(20, 7, now - 5 * day), now, zone)
        assertEquals(RelianceTrend(RelianceDirection.Same, 35, 30), same.trend)
    }

    @Test fun noTrendWithoutEnoughRepliesInEachHalf() {
        val min = TranslationReliance.MIN_TREND_REPLIES
        assertNull(TranslationReliance.of(reads(min - 1, 9, now - 40 * day) + reads(min, 2, now - 5 * day), now, zone).trend)
        assertNull(TranslationReliance.of(reads(min, 9, now - 40 * day) + reads(min - 1, 2, now - 5 * day), now, zone).trend)
        // Older than eight weeks is neither half.
        assertNull(TranslationReliance.of(reads(min, 9, now - 60 * day) + reads(min, 2, now - 5 * day), now, zone).trend)
    }

    @Test fun trendIsScopedToTheLanguage() {
        val es = reads(20, 10, now - 40 * day, "es") + reads(20, 2, now - 5 * day, "es")
        val ca = reads(20, 2, now - 5 * day, "ca")
        assertEquals(RelianceDirection.Down, TranslationReliance.of(es + ca, now, zone, "es").trend?.direction)
        assertNull(TranslationReliance.of(es + ca, now, zone, "ca").trend)
    }
}
