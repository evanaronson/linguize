package com.evanaronson.linguize.core.time

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lab, Time spent, estimator version 3: an unmeasured message's writing is estimated from the
 * writer's speed around the time it was written, the 20 measured chat messages nearest to it in
 * time, so estimates don't shift as the writer's speed changes later.
 */
class NearestSpeedTest {
    private val day = 86_400_000L
    private val t0 = 1_800_000_000_000L
    private var ids = 0

    /** A measured chat message of 10 words at [msPerWord], [days] from t0. */
    private fun chat(days: Double, msPerWord: Long, language: String? = "es", words: Int = 10) =
        TimeRecord("c${ids++}", "chat", language, msPerWord * words, 0, words, 0, 0, t0 + (days * day).toLong())

    @Test fun usesTheTwentyNearestBeforeAndAfter() {
        // 10 messages just before and 10 just after, at 2 s a word; slower ones further out on both sides.
        val near = (1..10).map { chat(-it * 0.1, 2_000) } + (1..10).map { chat(it * 0.1, 2_000) }
        val far = (1..15).map { chat(-30.0 - it, 8_000) } + (1..15).map { chat(30.0 + it, 8_000) }
        val estimate = WritingEstimator(near + far).estimate(10, "es", t0)
        assertEquals(WritingEstimate(20_000, EstimateBasis.Language, 2_000), estimate)
        // At the far end, the far messages decide it.
        assertEquals(8_000L, WritingEstimator(near + far).speed("es", t0 + 45 * day).first)
    }

    @Test fun selectsByDistanceEitherSideAndTiesGoEarlier() {
        val sorted = listOf(chat(-3.0, 1), chat(-1.0, 1), chat(1.0, 1), chat(2.0, 1), chat(5.0, 1))
            .sortedBy { it.at }
        val picked = WritingEstimator.nearest(sorted, t0, count = 3).map { it.at }
        assertEquals(listOf(t0 - day, t0 + day, t0 + 2 * day), picked)
        // Equal distance: the earlier one first.
        assertEquals(listOf(t0 - day), WritingEstimator.nearest(sorted, t0, count = 1).map { it.at })
        // All of them when there aren't more than asked for.
        assertEquals(sorted, WritingEstimator.nearest(sorted, t0, count = 5))
        // Past either end.
        assertEquals(listOf(t0 + 5 * day, t0 + 2 * day), WritingEstimator.nearest(sorted, t0 + 10 * day, count = 2).map { it.at })
        assertEquals(listOf(t0 - 3 * day, t0 - day), WritingEstimator.nearest(sorted, t0 - 10 * day, count = 2).map { it.at })
    }

    @Test fun fewerThanTwentyUsesAllInTheLanguage() {
        val records = (1..3).map { chat(-100.0 - it, 2_000) } + (1..3).map { chat(100.0 + it, 6_000) } + chat(200.0, 6_000)
        // Seven messages, all used wherever the message is: the word-weighted median is 6 s.
        assertEquals(6_000L, WritingEstimator(records).speed("es", t0 - 200 * day).first)
        assertEquals(6_000L, WritingEstimator(records).speed("es", t0).first)
    }

    @Test fun staysInTheLanguageWhenItHasEnough() {
        val spanish = (1..5).map { chat(it * 10.0, 3_000) }
        // Catalan right next to the message doesn't count for a Spanish one.
        val catalan = (1..20).map { chat(it * 0.01, 9_000, language = "ca") }
        val estimator = WritingEstimator(spanish + catalan)
        assertEquals(3_000L to EstimateBasis.Language, estimator.speed("es", t0))
        assertEquals(9_000L to EstimateBasis.Language, estimator.speed("ca", t0))
    }

    @Test fun belowTheThresholdsPoolsLanguagesByTheSameRule() {
        // Four Spanish messages (40 words): too few. Pooled with Catalan, the nearest 20 decide.
        val spanish = (1..4).map { chat(-it * 0.1, 2_000) }
        val catalanNear = (1..16).map { chat(it * 0.1, 2_000, language = "ca") }
        val catalanFar = (1..30).map { chat(100.0 + it, 9_000, language = "ca") }
        val estimator = WritingEstimator(spanish + catalanNear + catalanFar)
        assertEquals(2_000L to EstimateBasis.AllLanguages, estimator.speed("es", t0))
        assertEquals(9_000L to EstimateBasis.AllLanguages, estimator.speed("es", t0 + 120 * day))
        // A message with no language is estimated from the pool too.
        assertEquals(EstimateBasis.AllLanguages, estimator.basis(null))

        // Five messages but under 30 words (3 words each) is still too few.
        val short = (1..5).map { chat(it.toDouble(), 2_000, words = 3) }
        assertEquals(EstimateBasis.Default, WritingEstimator(short).basis("es"))
    }

    @Test fun theDefaultWithNothingMeasured() {
        val estimator = WritingEstimator(emptyList())
        assertEquals(WritingEstimate(40_000, EstimateBasis.Default, WritingEstimator.DEFAULT_MS_PER_WORD), estimator.estimate(10, "es", t0))
        // Estimates and short replies never teach the speed.
        val records = (1..30).map { TimeRecord("m$it", "menu", "es", null, 0, 20, 0, 0, t0) } + (1..30).map { chat(it.toDouble(), 1_000, words = 2) }
        assertEquals(EstimateBasis.Default, WritingEstimator(records).basis("es"))
    }

    @Test fun anOldEstimateDoesntChangeWhenManyNewMessagesArriveFarAway() {
        val then = (1..25).map { chat(it * 0.5 - 6.0, 5_000) }
        val oldCheck = UntimedMessage("old", "menu", "es", words = 12, at = t0)
        val before = WritingEstimator(then).writingOf(oldCheck)
        assertEquals(60_000L, before)
        // A year later the writer is much faster, with hundreds of messages.
        val later = (1..300).map { chat(365.0 + it * 0.1, 1_500) }
        assertEquals(before, WritingEstimator(then + later).writingOf(oldCheck))
        // A message written then is estimated at the speed of then, not of now.
        assertEquals(1_500L, WritingEstimator(then + later).speed("es", t0 + 380 * day).first)
    }

    @Test fun whetherALanguageHasEnoughDoesntDependOnTime() {
        val records = (1..6).map { chat(it.toDouble(), 3_000) }
        val estimator = WritingEstimator(records)
        listOf(t0 - 1_000 * day, t0, t0 + 1_000 * day).forEach {
            assertEquals(EstimateBasis.Language, estimator.speed("es", it).second)
        }
    }
}
