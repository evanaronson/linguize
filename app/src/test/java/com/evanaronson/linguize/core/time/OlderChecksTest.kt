package com.evanaronson.linguize.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Lab, Time spent: checks saved before Time spent existed (History sessions with no time
 * record) count with their writing estimated and their review not timed: never estimated,
 * never counted as zero in an average.
 */
class OlderChecksTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDate.of(2026, 11, 11).atTime(12, 0).toInstant(zone).toEpochMilli()
    private val day = 86_400_000L

    /** Five measured Spanish chat messages at 3 s a word, 10 s of review each, 2 changes offered. */
    private val measured = (1..5).map { TimeRecord("c$it", "chat", "es", 30_000, 10_000, 10, 2, 1, now - it * day) }

    private fun old(id: String, words: Int, language: String? = "es", at: Long = now - 300 * day) =
        UntimedMessage(id, "menu", language, words, at)

    @Test fun olderChecksCountWithEstimatedWritingAndNoReview() {
        val report = TimeReport.of(measured, now, zone, untimed = listOf(old("o1", 20), old("o2", 10)))
        val all = report.allTime
        assertEquals(7, all.messages)
        assertEquals(150_000L, all.measuredWritingMs)
        // 30 words at the writer's 3 s a word, the only speed there is.
        assertEquals(90_000L, all.estimatedWritingMs)
        assertEquals(2, all.estimatedMessages)
        // Review is measured only, and says what it doesn't cover.
        assertEquals(50_000L, all.reviewMs)
        assertEquals(2, all.reviewUntimedMessages)
        assertEquals(290_000L, all.totalMs)
    }

    @Test fun averagesLeaveOutUntimedReview() {
        val report = TimeReport.of(measured, now, zone, untimed = (1..20).map { old("o$it", 1) })
        // Per message only over timed messages: 30 s writing + 10 s review; the 20 quick older ones
        // (3 s of writing, no review) don't drag it down.
        assertEquals(40_000L, report.msPerMessage)
        // Per word and per change stay measured-only.
        assertEquals(3_000L, report.msPerWord)
        assertEquals(5_000L, report.msPerChange)
        assertEquals(10, report.changesShown)
        // Only untimed messages: no average per message at all.
        assertNull(TimeReport.of(emptyList(), now, zone, untimed = listOf(old("o", 5))).msPerMessage)
    }

    @Test fun aSessionWithARecordIsntCountedTwice() {
        val report = TimeReport.of(measured, now, zone, untimed = listOf(old("c1", 50)))
        assertEquals(5, report.allTime.messages)
        assertEquals(0, report.allTime.reviewUntimedMessages)
    }

    @Test fun languageScopeWeeksAndLanguages() {
        val untimed = listOf(old("es-old", 10), old("ca-old", 10, language = "ca", at = now), old("none", 10, language = null))
        val catalan = TimeReport.of(measured, now, zone, language = "ca", untimed = untimed)
        assertEquals(1, catalan.allTime.messages)
        assertEquals(1, catalan.thisWeek.reviewUntimedMessages)
        // Estimated from the writer's measurements in every language: Catalan has none, so all languages.
        assertEquals(30_000L, catalan.thisWeek.estimatedWritingMs)
        assertEquals(EstimateBasis.AllLanguages, catalan.languages.single().estimateBasis)
        assertNull(catalan.languages.single().msPerWord)
        assertEquals(30_000L, catalan.weeks.last().totals.estimatedWritingMs)

        val all = TimeReport.of(measured, now, zone, untimed = untimed)
        assertEquals(8, all.allTime.messages)
        assertEquals(6, all.languages.single { it.language == "es" }.totals.messages)
        assertEquals(3_000L, all.languages.single { it.language == "es" }.msPerWord)
    }

    @Test fun deletingAnOlderCheckRemovesItsContribution() {
        val history = listOf(old("o1", 20), old("o2", 10))
        val before = TimeReport.of(measured, now, zone, untimed = history)
        // History no longer has o1: it's derived, so it's simply not there on the next read.
        val after = TimeReport.of(measured, now, zone, untimed = history.filter { it.sessionId != "o1" })
        assertEquals(before.allTime.messages - 1, after.allTime.messages)
        assertEquals(before.allTime.estimatedWritingMs - 60_000L, after.allTime.estimatedWritingMs)
        assertEquals(1, after.allTime.reviewUntimedMessages)
    }

    @Test fun anOlderChecksOwnLineMatchesWhatTheTotalsCount() {
        val check = old("o1", 20)
        assertEquals(MessageTime(60_000, writingEstimated = true, reviewMs = null), MessageTime.of(check, measured))
        assertEquals(60_000L, TimeReport.of(measured, now, zone, untimed = listOf(check)).allTime.estimatedWritingMs)
        // A session that has a record after all uses it.
        assertEquals(MessageTime(30_000, writingEstimated = false, reviewMs = 10_000), MessageTime.of(old("c1", 99), measured))
    }

    @Test fun wordsComeFromTheWholeOriginalTextWithTheSharedTokenizer() {
        val text = "Hola, com estàs? " + "molt bé ".repeat(200)
        assertEquals(TimeRecord.countWords(text), UntimedMessage.of("s", "menu", "ca", text, now).words)
        assertEquals(403, UntimedMessage.of("s", "menu", "ca", text, now).words)
    }
}
