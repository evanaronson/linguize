package com.evanaronson.linguize.ui.history

import com.evanaronson.linguize.ui.components.DateLocale
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Test

/** Recent and the detail page share one date format, always in English. */
class HistoryLabelsTest {
    private val zone = ZoneId.of("UTC")
    private val time = DateTimeFormatter.ofPattern("HH:mm", DateLocale)
    private fun at(y: Int, m: Int, d: Int) = ZonedDateTime.of(y, m, d, 14, 2, 0, 0, zone).toInstant().toEpochMilli()
    private val now = at(2026, 10, 10)

    @Test
    fun recentShowsTheTimeForTodayAndYesterdayOnly() {
        assertEquals("Today, 14:02", relativeDate(at(2026, 10, 10), now, time, zone))
        assertEquals("Yesterday, 14:02", relativeDate(at(2026, 10, 9), now, time, zone))
        assertEquals("1 Oct", relativeDate(at(2026, 10, 1), now, time, zone))
        assertEquals("9 Oct 2025", relativeDate(at(2025, 10, 9), now, time, zone))
    }

    @Test
    fun theDetailPageAddsTheTimeToOlderDates() {
        assertEquals("1 Oct, 14:02", relativeDate(at(2026, 10, 1), now, time, zone, alwaysTime = true))
        assertEquals("Today, 14:02", relativeDate(at(2026, 10, 10), now, time, zone, alwaysTime = true))
    }
}
