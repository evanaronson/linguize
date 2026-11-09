package com.evanaronson.linguize.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveTimerTest {
    private var now = 1_000L
    private val timer = ActiveTimer(idleCapMs = 60_000L) { now }

    @Test fun countsFromStartThroughTouchesToStop() {
        timer.start()
        now += 5_000
        timer.touch()
        now += 3_000
        assertEquals(8_000L, timer.stop())
        assertFalse(timer.started)
        assertEquals(0L, timer.elapsed())
    }

    @Test fun touchStartsTheTimerWithoutCountingTheTimeBefore() {
        now += 100_000
        timer.touch()
        now += 2_000
        assertEquals(2_000L, timer.elapsed())
    }

    @Test fun eachIdleGapCountsAtMostTheCap() {
        timer.start()
        now += 10 * 60_000
        timer.touch()
        now += 4_000
        timer.touch()
        assertEquals(64_000L, timer.elapsed())
        // An open gap is capped too.
        now += 30 * 60_000
        assertEquals(124_000L, timer.stop())
    }

    @Test fun nothingCountsWhilePaused() {
        timer.start()
        now += 2_000
        timer.pause()
        assertFalse(timer.running)
        now += 50_000
        timer.touch()
        assertEquals(2_000L, timer.elapsed())
        timer.resume()
        now += 1_000
        assertEquals(3_000L, timer.elapsed())
    }

    @Test fun nothingCountsWhileHiddenAndHidingCountsUpToThen() {
        timer.start()
        now += 2_000
        timer.visible(false)
        now += 10 * 60_000
        assertEquals(2_000L, timer.elapsed())
        timer.visible(true)
        assertTrue(timer.running)
        now += 1_000
        assertEquals(3_000L, timer.elapsed())
    }

    @Test fun hiddenAndPausedAreSeparateReasons() {
        timer.start()
        timer.pause()
        timer.visible(false)
        timer.visible(true)
        now += 5_000
        assertEquals(0L, timer.elapsed())
        timer.resume()
        now += 5_000
        assertEquals(5_000L, timer.elapsed())
    }

    @Test fun resumeBeforeStartDoesNothing() {
        timer.resume()
        now += 5_000
        assertFalse(timer.started)
        assertEquals(0L, timer.elapsed())
    }

    @Test fun aClockGoingBackwardsCountsNothing() {
        timer.start()
        now -= 5_000
        assertEquals(0L, timer.elapsed())
    }
}
