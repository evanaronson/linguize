package com.evanaronson.linguize.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lab, Time spent: one message's own time, and averages that one unusually slow message (left
 * open within the idle caps) barely moves, while totals still include it.
 */
class RobustAveragesTest {
    private val now = 1_800_000_000_000L

    private fun chat(id: String, writing: Long, words: Int, review: Long = 0, shown: Int = 0) =
        TimeRecord(id, "chat", "es", writing, review, words, shown, 0, now)

    private fun menu(id: String, words: Int, review: Long) = TimeRecord(id, "menu", "es", null, review, words, 0, 0, now)

    @Test fun medians() {
        assertNull(Median.of(emptyList()))
        assertEquals(2L, Median.of(listOf(3, 1, 2)))
        assertEquals(25L, Median.of(listOf(10, 20, 30, 40)))
        // Weighted: the value at which half the weight lies on each side.
        assertEquals(1.0, Median.weighted(listOf(1.0 to 3.0, 10.0 to 2.0))!!, 0.0)
        assertEquals(5.5, Median.weighted(listOf(1.0 to 1.0, 10.0 to 1.0))!!, 0.0)
        assertNull(Median.weighted(listOf(4.0 to 0.0)))
    }

    @Test fun anOutlierMessageBarelyMovesTheAveragesButCountsInTheTotals() {
        // Nine ordinary messages: 10 words in 40 s, 2 changes reviewed in 12 s.
        val usual = (1..9).map { chat("c$it", writing = 40_000, words = 10, review = 12_000, shown = 2) }
        // One left open: ten times as long everywhere, still within what the caps allow.
        val slow = chat("slow", writing = 400_000, words = 10, review = 120_000, shown = 2)
        val before = TimeReport.of(usual, now)
        val after = TimeReport.of(usual + slow, now)

        assertEquals(52_000L, before.msPerMessage)
        assertEquals(52_000L, after.msPerMessage)
        assertEquals(4_000L, after.msPerWord)
        assertEquals(6_000L, after.msPerChange)
        // A mean would have moved per message from 52 s to 98.8 s.
        assertTrue(after.allTime.totalMs / after.allTime.messages > 90_000L)
        // Totals are true sums: the slow message is all there.
        assertEquals(before.allTime.totalMs + 520_000L, after.allTime.totalMs)
        assertEquals(10, after.allTime.messages)
        assertEquals(4_000L, after.languages.single().msPerWord)
    }

    @Test fun perWordIsWeightedByWordsSoShortRepliesDontDecideIt() {
        // Two long messages at 3 s a word outweigh three two-word replies at 10 s a word.
        val records = listOf(chat("a", 60_000, 20), chat("b", 60_000, 20)) + (1..3).map { chat("r$it", 20_000, 2) }
        assertEquals(3_000L, TimeReport.of(records, now).msPerWord)
    }

    @Test fun anOutlierDoesntSlowOtherAppsEstimates() {
        val usual = (1..6).map { chat("c$it", writing = 30_000, words = 10) }
        val slow = chat("slow", writing = 300_000, words = 10)
        assertEquals(3_000L, WritingEstimator(usual + slow).speed("es").first)
    }

    @Test fun oneMessagesTimeIsMeasuredInTheChatAndEstimatedElsewhere() {
        val records = (1..5).map { chat("c$it", writing = 30_000, words = 10, review = 5_000) } + menu("m", words = 8, review = 35_000)
        assertEquals(MessageTime(30_000, writingEstimated = false, reviewMs = 5_000), MessageTime.of("c1", records))
        // Other apps: words times the writer's own chat speed (3 s a word here), labelled estimated.
        assertEquals(MessageTime(24_000, writingEstimated = true, reviewMs = 35_000), MessageTime.of("m", records))
        // Nothing recorded (an older check, or History off): nothing to show.
        assertNull(MessageTime.of("older", records))
    }
}
