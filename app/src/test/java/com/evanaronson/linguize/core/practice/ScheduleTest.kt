package com.evanaronson.linguize.core.practice

import com.evanaronson.linguize.core.practice.Schedule.DAY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {
    private val now = 1_000_000_000_000L

    @Test
    fun rightAnswersGoOneDayThreeDaysThenByEase() {
        var state = Schedule.review(null, Grade.Good, now)
        assertEquals(1, state.intervalDays)
        assertEquals(now + DAY, state.due)
        state = Schedule.review(state, Grade.Good, state.due)
        assertEquals(3, state.intervalDays)
        val ease = state.ease
        val at = state.due
        state = Schedule.review(state, Grade.Good, at)
        assertEquals(Math.round(3 * ease).toInt(), state.intervalDays)
        assertEquals(at + state.intervalDays * DAY, state.due)
        assertEquals(3, state.streak)
        assertEquals(3, state.reviews)
    }

    @Test
    fun aMissComesBackInTenMinutesAndLowersEase() {
        val learned = Schedule.review(Schedule.review(null, Grade.Good, now), Grade.Good, now + DAY)
        val missed = Schedule.review(learned, Grade.Again, now + 4 * DAY)
        assertEquals(now + 4 * DAY + Schedule.RETRY_AFTER, missed.due)
        assertEquals(0, missed.intervalDays)
        assertEquals(0, missed.streak)
        assertEquals(1, missed.lapses)
        assertEquals(learned.ease - 0.2, missed.ease, 1e-9)
        // After a miss, a right answer starts again at one day.
        assertEquals(1, Schedule.review(missed, Grade.Good, missed.due).intervalDays)
    }

    @Test
    fun aMissOnANewItemIsNoLapse() {
        assertEquals(0, Schedule.review(null, Grade.Again, now).lapses)
    }

    @Test
    fun aNearMissGrowsSlowly() {
        val first = Schedule.review(null, Grade.Hard, now)
        assertEquals(1, first.intervalDays)
        assertEquals(Schedule.START_EASE - 0.15, first.ease, 1e-9)
        val long = ReviewState(intervalDays = 10, streak = 3, due = now, reviewedAt = now)
        assertEquals(12, Schedule.review(long, Grade.Hard, now).intervalDays)
        val short = ReviewState(intervalDays = 1, streak = 1, due = now, reviewedAt = now)
        assertEquals(2, Schedule.review(short, Grade.Hard, now).intervalDays)
    }

    @Test
    fun easeStaysWithinBounds() {
        var state: ReviewState? = null
        repeat(20) { state = Schedule.review(state, Grade.Again, now) }
        assertEquals(Schedule.MIN_EASE, state!!.ease, 1e-9)
        repeat(40) { state = Schedule.review(state, Grade.Good, now) }
        assertEquals(Schedule.MAX_EASE, state!!.ease, 1e-9)
    }

    @Test
    fun intervalsAreCapped() {
        val state = ReviewState(intervalDays = 150, streak = 8, ease = 3.0, due = now, reviewedAt = now)
        val next = Schedule.review(state, Grade.Good, now)
        assertEquals(Schedule.MAX_INTERVAL_DAYS, next.intervalDays)
        assertTrue(next.due <= now + Schedule.MAX_INTERVAL_DAYS * DAY)
    }
}
