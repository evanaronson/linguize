package com.evanaronson.linguize.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Lab, Time spent: Speed over time. */
class SpeedTrendTest {
    private val zone = ZoneOffset.UTC

    // Wednesday 11 November 2026, noon UTC: this week starts Monday 9 November.
    private val now = LocalDate.of(2026, 11, 11).atTime(12, 0).toInstant(zone).toEpochMilli()
    private val week = 7 * 86_400_000L
    private val thisMonday = LocalDate.of(2026, 11, 9)
    private var ids = 0

    /** A measured chat message [weeksAgo] weeks back (same weekday), written at [msPerWord]. */
    private fun chat(
        weeksAgo: Int,
        msPerWord: Long = 3_000,
        words: Int = 10,
        language: String? = "es",
        rules: Int = TimeRecord.RULES_VERSION,
        review: Long = 0,
        shown: Int = 0,
    ) = TimeRecord("c${ids++}", "chat", language, msPerWord * words, review, words, shown, 0, now - weeksAgo * week, rules)

    private fun menu(weeksAgo: Int, words: Int = 10, language: String? = "es", review: Long = 0, shown: Int = 0) =
        TimeRecord("m${ids++}", "menu", language, null, review, words, shown, 0, now - weeksAgo * week)

    /** [count] messages in the week [weeksAgo] weeks back. */
    private fun week(weeksAgo: Int, msPerWord: Long = 3_000, count: Int = 5, words: Int = 10, language: String? = "es", rules: Int = 2) =
        List(count) { chat(weeksAgo, msPerWord, words, language, rules) }

    private fun trend(records: List<TimeRecord>, scope: String? = "es") = SpeedTrend.of(records, now, zone, scope)

    // Periods and the minimum.

    @Test fun oneWeekPerPointOverTheLastEightWeeks() {
        val trend = trend((0..7).flatMap { week(it) })
        assertEquals(1, trend.weeksPerPoint)
        assertEquals(SpeedTrend.WEEKS, trend.points.size)
        assertEquals(thisMonday.minusWeeks(7), trend.points.first().start)
        assertEquals(thisMonday, trend.points.last().start)
        assertTrue(trend.points.all { it.msPerWord == 3_000L && it.messages == 5 && it.words == 50 })
    }

    @Test fun olderThanTheWindowDoesntCount() {
        val trend = trend(week(8, msPerWord = 9_000) + week(0))
        assertEquals(listOf(3_000L), trend.points.mapNotNull { it.msPerWord })
    }

    @Test fun aWeekNeedsFiveMessagesAndThirtyWords() {
        val records = week(5) + week(4) + // full weeks, so most weeks with writing are enough and points stay weekly
            week(3, count = 4) + // 4 messages: thin
            week(2, count = 5, words = 5) + // 25 words: thin
            week(1, count = 5, words = 6) + // 30 words: enough
            week(0, count = 9, words = 2) // replies of two words never count
        val points = trend(records).points.takeLast(4)
        assertEquals(1, trend(records).weeksPerPoint)
        assertEquals(listOf(null, null, 3_000L, null), points.map { it.msPerWord })
        assertEquals(listOf(4, 5, 5, 0), points.map { it.messages })
    }

    @Test fun twoWeeksPerPointWhenMostWeeksAreThin() {
        val trend = trend((0..7).flatMap { week(it, count = 3) })
        assertEquals(2, trend.weeksPerPoint)
        assertEquals((3 downTo 0).map { thisMonday.minusWeeks(2L * it + 1) }, trend.points.map { it.start })
        assertTrue("Two thin weeks make one point", trend.points.all { it.messages == 6 && it.msPerWord == 3_000L })
    }

    @Test fun aNewWriterWithFewButFullWeeksStaysWeekly() {
        val trend = trend((0..2).flatMap { week(it) })
        assertEquals(1, trend.weeksPerPoint)
        assertEquals(3, trend.points.count { it.msPerWord != null })
    }

    @Test fun oneSlowMessageBarelyMovesItsWeek() {
        val records = week(0, count = 5) + chat(0, msPerWord = 30_000, words = 10) // left open, within the caps
        assertEquals(3_000L, trend(records).points.last().msPerWord)
    }

    @Test fun estimatedWritingNeverFeedsIt() {
        val onlyOtherApps = (0..7).flatMap { weeksAgo -> List(10) { menu(weeksAgo, words = 20) } }
        val trend = trend(onlyOtherApps)
        assertFalse(trend.hasWriting)
        assertTrue(trend.points.all { it.messages == 0 })

        val mixed = trend(week(0) + onlyOtherApps)
        assertEquals(3_000L, mixed.points.last().msPerWord)
        assertEquals(5, mixed.points.last().messages)
    }

    // The cap change.

    @Test fun writingUnderTheOldCapIsLeftOutAndCounted() {
        val records = (4..7).flatMap { week(it, msPerWord = 6_000, rules = 1) } + (0..3).flatMap { week(it) }
        val trend = trend(records)
        assertEquals(listOf(null, null, null, null, 3_000L, 3_000L, 3_000L, 3_000L), trend.points.map { it.msPerWord })
        assertEquals(20, trend.leftOut)
        val change = trend.change!!
        assertEquals("Compares the new rules with themselves, never 6 s against 3 s", SpeedDirection.Same, change.direction)
        assertEquals(3, change.weeksAgo)
    }

    @Test fun reviewIsComparableAcrossTheCapChange() {
        val oldCards = List(5) { chat(5, rules = 1, review = 12_000, shown = 2) }
        assertEquals(6_000L, trend(oldCards).points[2].msPerChange)
    }

    @Test fun theOldestComparableRulesAreTheCurrentWritingRules() {
        assertTrue(SpeedTrend.WRITING_RULES <= TimeRecord.RULES_VERSION)
        assertEquals(2, SpeedTrend.WRITING_RULES)
    }

    // Review.

    @Test fun reviewIsPerChangeOfferedFromEverySourceWithFiveCards() {
        val four = List(4) { menu(0, review = 10_000, shown = 2) }
        assertNull(trend(four).points.last().msPerChange)

        val records = four + chat(0, review = 5_000, shown = 1) + menu(0, review = 99_000, shown = 0)
        val point = trend(records).points.last()
        assertEquals("Cards with no change don't count", 5, point.reviewMessages)
        assertEquals(5_000L, point.msPerChange)
    }

    @Test fun oneSlowReviewBarelyMovesItsWeek() {
        val records = List(5) { menu(0, review = 8_000, shown = 2) } + menu(0, review = 120_000, shown = 2)
        assertEquals(4_000L, trend(records).points.last().msPerChange)
    }

    // The summary.

    private fun change(before: Long, after: Long, weeksApart: Int = 7) =
        trend(week(weeksApart, msPerWord = before) + week(0, msPerWord = after)).change

    @Test fun fasterSlowerOrAboutTheSame() {
        assertEquals(SpeedChange(SpeedDirection.Faster, 20, 7), change(4_000, 3_200))
        assertEquals(SpeedChange(SpeedDirection.Slower, 20, 7), change(4_000, 4_800))
        assertEquals(SpeedChange(SpeedDirection.Same, 0, 7), change(4_000, 4_300))
        assertEquals(SpeedChange(SpeedDirection.Same, 0, 7), change(4_000, 3_640))
        assertEquals("10% is the edge", SpeedChange(SpeedDirection.Faster, 10, 7), change(4_000, 3_600))
        assertEquals("Rounded to 5", SpeedChange(SpeedDirection.Faster, 35, 7), change(4_000, 2_600))
        assertEquals(SpeedChange(SpeedDirection.Slower, 100, 7), change(3_000, 6_000))
    }

    @Test fun comparesTheEarliestAndLatestPointsWithEnoughData() {
        val records = week(6, msPerWord = 5_000) + week(4, msPerWord = 9_000) + week(1, msPerWord = 4_000) +
            List(2) { chat(0, msPerWord = 1_000) } // this week is thin: last week is the latest
        assertEquals(SpeedChange(SpeedDirection.Faster, 20, 5), trend(records).change)
    }

    @Test fun noClaimWithoutEnough() {
        assertNull("One point", trend(week(0)).change)
        assertNull("Nothing measured", trend(emptyList()).change)
        assertNull("A message or two either side", trend(List(2) { chat(6, msPerWord = 9_000) } + List(2) { chat(0) }).change)
        assertNull("One week apart", trend(week(1, msPerWord = 6_000) + week(0)).change)
        assertNull("The latest is not recent", trend(week(7, msPerWord = 6_000) + week(2)).change)
        assertEquals(SpeedDirection.Faster, trend(week(7, msPerWord = 6_000) + week(1)).change?.direction)
    }

    @Test fun twoWeekPointsCompareTooWhenTheyReachThisWeek() {
        val records = (6..7).flatMap { week(it, msPerWord = 5_000, count = 3) } + (2..5).flatMap { week(it, count = 1) } +
            (0..1).flatMap { week(it, msPerWord = 4_000, count = 3) }
        val trend = trend(records)
        assertEquals(2, trend.weeksPerPoint)
        assertEquals(SpeedChange(SpeedDirection.Faster, 20, 6), trend.change)
    }

    // Language.

    @Test fun aScopedLanguageOnly() {
        val records = week(7, msPerWord = 6_000, language = "ca") + week(0, msPerWord = 3_000, language = "ca") +
            (0..7).flatMap { week(it, msPerWord = 9_000, count = 10, language = "es") } + week(0, msPerWord = 100, language = null)
        val trend = trend(records, scope = "ca")
        assertEquals("ca", trend.language)
        assertFalse(trend.dominant)
        assertEquals(listOf(6_000L, 3_000L), trend.points.mapNotNull { it.msPerWord })
        assertEquals(SpeedDirection.Faster, trend.change?.direction)
    }

    @Test fun underLinguizeTheMostWrittenLanguage() {
        val records = week(0, language = "ca", words = 20) + week(0, msPerWord = 5_000, language = "es", count = 9) +
            List(20) { chat(0, words = 30, language = null) }
        val trend = trend(records, scope = null)
        assertEquals("Most words, not most messages; never an unknown language", "ca", trend.language)
        assertTrue(trend.dominant)
        assertEquals(3_000L, trend.points.last().msPerWord)
    }

    @Test fun underLinguizeWithNothingMeasured() {
        val trend = trend(List(10) { menu(0) }, scope = null)
        assertNull(trend.language)
        assertTrue(trend.points.isEmpty())
        assertNull(trend.change)
    }

    @Test fun theReportCarriesTheTrendForItsLanguage() {
        val records = week(0, language = "ca") + week(0, msPerWord = 5_000, language = "es")
        assertEquals(5_000L, TimeReport.of(records, now, zone, "es").speed.points.last().msPerWord)
        assertEquals("ca", TimeReport.of(records, now, zone, "ca").speed.language)
    }
}
