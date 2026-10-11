package com.evanaronson.linguize.menu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckRateLimitTest {
    private var time = 0L
    private val limit = CheckRateLimit(perApp = 3, overall = 5, windowMillis = 60_000, now = { time })

    @Test
    fun anAppGetsItsAllowanceThenWaits() {
        repeat(3) { assertTrue(limit.tryAcquire("com.chat")) }
        assertFalse(limit.tryAcquire("com.chat"))
    }

    @Test
    fun aRefusedCheckDoesNotCount() {
        repeat(3) { assertTrue(limit.tryAcquire("com.chat")) }
        time = 30_000
        repeat(10) { assertFalse(limit.tryAcquire("com.chat")) }
        // The first three leave the window a minute after they started, not after the refusals.
        time = 60_000
        assertTrue(limit.tryAcquire("com.chat"))
    }

    @Test
    fun theWindowSlides() {
        assertTrue(limit.tryAcquire("com.chat"))
        time = 20_000
        assertTrue(limit.tryAcquire("com.chat"))
        assertTrue(limit.tryAcquire("com.chat"))
        time = 59_999
        assertFalse(limit.tryAcquire("com.chat"))
        time = 60_000
        assertTrue(limit.tryAcquire("com.chat"))
        assertFalse(limit.tryAcquire("com.chat"))
    }

    @Test
    fun eachAppHasItsOwnAllowance() {
        repeat(3) { assertTrue(limit.tryAcquire("com.chat")) }
        assertTrue(limit.tryAcquire("com.mail"))
    }

    @Test
    fun allAppsTogetherHitTheOverallCap() {
        repeat(3) { assertTrue(limit.tryAcquire("com.chat")) }
        repeat(2) { assertTrue(limit.tryAcquire("com.mail")) }
        assertFalse(limit.tryAcquire("com.notes"))
    }

    /** Apps that don't ask for a result have no known package; they share one allowance. */
    @Test
    fun unknownCallersShareOneAllowance() {
        repeat(3) { assertTrue(limit.tryAcquire(null)) }
        assertFalse(limit.tryAcquire(null))
        assertTrue(limit.tryAcquire("com.chat"))
    }
}
