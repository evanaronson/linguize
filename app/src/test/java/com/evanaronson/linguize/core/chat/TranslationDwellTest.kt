package com.evanaronson.linguize.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lab, Translation reliance: a translation counts as needed only after it was readable long enough, once. */
class TranslationDwellTest {
    private var now = 10_000L
    private val dwell = TranslationDwell({ now })

    @Test fun aQuickFlipBackDoesNotCount() {
        dwell.shown("r1", true)
        now += 900
        dwell.shown("r1", false)
        now += 10_000
        assertTrue(dwell.drain().isEmpty())
        assertNull(dwell.remainingMs())
    }

    @Test fun aTranslationLeftOpenCountsOnceTheThresholdPasses() {
        dwell.shown("r1", true)
        now += TranslationDwell.NEEDED_MS - 1
        assertTrue(dwell.drain().isEmpty())
        assertEquals(1L, dwell.remainingMs())
        now += 1
        assertEquals(listOf("r1"), dwell.drain())
        assertNull(dwell.remainingMs())
    }

    @Test fun flippingBackAfterTheThresholdStillCounts() {
        dwell.shown("r1", true)
        now += 3_000
        dwell.shown("r1", false)
        assertEquals(listOf("r1"), dwell.drain())
    }

    @Test fun quickTapsDoNotAddUp() {
        repeat(5) {
            dwell.shown("r1", true)
            now += 1_000
            dwell.shown("r1", false)
            now += 200
        }
        assertTrue(dwell.drain().isEmpty())
    }

    @Test fun leavingTheAppPausesTheTimer() {
        dwell.shown("r1", true)
        now += 1_500
        dwell.visible(false)
        now += 60_000 // in another app: not reading
        assertTrue(dwell.drain().isEmpty())
        assertNull(dwell.remainingMs())
        dwell.visible(true)
        assertEquals(1_000L, dwell.remainingMs())
        now += 999
        assertTrue(dwell.drain().isEmpty())
        now += 1
        assertEquals(listOf("r1"), dwell.drain())
    }

    @Test fun aBubbleScrolledAwayPausesTheTimer() {
        dwell.onScreen(setOf("r1", "w1"))
        dwell.shown("r1", true)
        now += 2_000
        dwell.onScreen(setOf("w1"))
        now += 30_000
        assertTrue(dwell.drain().isEmpty())
        dwell.onScreen(setOf("r1"))
        now += 500
        assertEquals(listOf("r1"), dwell.drain())
    }

    @Test fun aTranslationShownOffScreenStartsOnlyOnceItIsOnScreen() {
        dwell.onScreen(setOf("w1"))
        dwell.shown("r1", true)
        now += 10_000
        assertTrue(dwell.drain().isEmpty())
        dwell.onScreen(setOf("r1"))
        now += 2_500
        assertEquals(listOf("r1"), dwell.drain())
    }

    @Test fun aMessageCountsOnceHoweverOftenItIsFlipped() {
        dwell.shown("r1", true)
        now += 3_000
        dwell.shown("r1", false)
        assertEquals(listOf("r1"), dwell.drain())
        dwell.shown("r1", true)
        now += 10_000
        dwell.shown("r1", false)
        assertTrue(dwell.drain().isEmpty())
        assertTrue(dwell.isCounted("r1"))
    }

    @Test fun eachMessageIsTimedOnItsOwn() {
        dwell.shown("r1", true)
        now += 1_000
        dwell.shown("r2", true)
        assertEquals(1_500L, dwell.remainingMs())
        now += 1_500
        assertEquals(listOf("r1"), dwell.drain())
        now += 1_000
        assertEquals(listOf("r2"), dwell.drain())
    }

    @Test fun reachingTheThresholdWhileLeavingStillCounts() {
        dwell.shown("r1", true)
        now += 2_600
        // The host's wake-up hadn't run yet: the time up to leaving is counted when it leaves.
        dwell.visible(false)
        assertEquals(listOf("r1"), dwell.drain())
    }
}
