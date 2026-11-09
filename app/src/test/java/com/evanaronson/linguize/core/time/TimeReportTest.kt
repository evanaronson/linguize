package com.evanaronson.linguize.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class TimeReportTest {
    private val zone = ZoneOffset.UTC

    // Wednesday 11 November 2026, noon UTC.
    private val now = LocalDate.of(2026, 11, 11).atTime(12, 0).toInstant(zone).toEpochMilli()
    private val day = 86_400_000L

    private fun chat(id: String, writing: Long, words: Int, language: String? = "es", review: Long = 0, shown: Int = 0, at: Long = now) =
        TimeRecord(id, "chat", language, writing, review, words, shown, 0, at)

    private fun menu(id: String, words: Int, language: String? = "es", review: Long = 0, shown: Int = 0, accepted: Int = 0, at: Long = now) =
        TimeRecord(id, "menu", language, null, review, words, shown, accepted, at)

    @Test fun countsWordsWithTheSharedTokenizer() {
        assertEquals(4, TimeRecord.countWords("¿Vienes a l'escola?"))
        assertEquals(0, TimeRecord.countWords("jajaja 123"))
    }

    // Estimator.

    @Test fun estimatesFromTheWritersSpeedInThatLanguage() {
        val measured = (1..5).map { chat("c$it", writing = 30_000, words = 10) } + // 3 s a word in Spanish
            (1..5).map { chat("k$it", writing = 60_000, words = 10, language = "ca") } // 6 s a word in Catalan
        val estimator = WritingEstimator(measured)
        assertEquals(WritingEstimate(60_000, EstimateBasis.Language, 3_000), estimator.estimate(20, "es"))
        assertEquals(WritingEstimate(120_000, EstimateBasis.Language, 6_000), estimator.estimate(20, "ca"))
    }

    @Test fun fallsBackToAllLanguagesThenToTheDefault() {
        val few = (1..3).map { chat("c$it", writing = 40_000, words = 10) }
        assertEquals(EstimateBasis.Default, WritingEstimator(few).estimate(10, "es").basis)
        assertEquals(WritingEstimator.DEFAULT_MS_PER_WORD * 10, WritingEstimator(few).estimate(10, "es").ms)

        val mixed = few + (1..2).map { chat("k$it", writing = 40_000, words = 10, language = "ca") }
        val estimate = WritingEstimator(mixed).estimate(10, "es")
        assertEquals(EstimateBasis.AllLanguages, estimate.basis)
        assertEquals(40_000L, estimate.ms)
    }

    @Test fun shortMessagesAndEstimatesDontTeachTheSpeed() {
        val records = (1..10).map { chat("c$it", writing = 20_000, words = 2) } + (1..10).map { menu("m$it", words = 20) }
        assertEquals(EstimateBasis.Default, WritingEstimator(records).speed("es").second)
    }

    @Test fun measuredWritingIsNeverReplacedByAnEstimate() {
        val record = chat("c", writing = 1_234, words = 50)
        assertEquals(1_234L, WritingEstimator(listOf(record)).writingOf(record))
    }

    // Report.

    @Test fun anEmptyReport() {
        val report = TimeReport.of(emptyList(), now, zone)
        assertTrue(report.isEmpty)
        assertNull(report.msPerWord)
        assertNull(report.msPerChange)
        assertNull(report.allTime.perMessageMs)
        assertEquals(TimeReport.WEEKS, report.weeks.size)
        assertEquals(EstimateBasis.Default, report.defaultBasis)
    }

    @Test fun splitsWritingIntoMeasuredAndEstimatedAndAddsReview() {
        val records = listOf(
            chat("c1", writing = 40_000, words = 10, review = 20_000, shown = 2),
            menu("m1", words = 5, review = 10_000, shown = 3, accepted = 2),
        )
        val report = TimeReport.of(records, now, zone)
        val all = report.allTime
        assertEquals(2, all.messages)
        assertEquals(40_000L, all.measuredWritingMs)
        assertEquals(5 * WritingEstimator.DEFAULT_MS_PER_WORD, all.estimatedWritingMs)
        assertEquals(30_000L, all.reviewMs)
        assertEquals(1, all.estimatedMessages)
        assertEquals(70_000L + 20_000L, all.totalMs)
        assertEquals(45_000L, all.perMessageMs)
        // Seconds per word come from measured writing only.
        assertEquals(4_000L, report.msPerWord)
        // Review time per change offered, over cards that offered any.
        assertEquals(6_000L, report.msPerChange)
        assertEquals(5, report.changesShown)
        assertEquals(2, report.changesAccepted)
    }

    @Test fun cleanMessagesDontDiluteTimePerChange() {
        val records = listOf(chat("c1", 10_000, 5, review = 0, shown = 0), menu("m1", 5, review = 8_000, shown = 2))
        assertEquals(4_000L, TimeReport.of(records, now, zone).msPerChange)
    }

    @Test fun groupsByMondayWeeksAndKeepsTheLastEight() {
        val monday = LocalDate.of(2026, 11, 9)
        val records = listOf(
            chat("now", 10_000, 5),
            chat("monday", 20_000, 5, at = monday.atStartOfDay(zone).toInstant().toEpochMilli() + 1),
            chat("sunday", 30_000, 5, at = monday.atStartOfDay(zone).toInstant().toEpochMilli() - 1),
            chat("old", 50_000, 5, at = now - 70 * day),
        )
        val report = TimeReport.of(records, now, zone)
        assertEquals(monday, report.weeks.last().start)
        assertEquals(30_000L, report.thisWeek.totalMs)
        assertEquals(30_000L, report.weeks[report.weeks.size - 2].totals.totalMs)
        assertEquals(monday.minusWeeks(7), report.weeks.first().start)
        // Too old for the chart, still in all time.
        assertEquals(60_000L, report.weeks.sumOf { it.totals.totalMs })
        assertEquals(110_000L, report.allTime.totalMs)
    }

    @Test fun perLanguageTotalsSpeedAndBasis() {
        val records = (1..5).map { chat("c$it", 30_000, 10) } + menu("m", 10, language = "ca") + menu("x", 10, language = null)
        val report = TimeReport.of(records, now, zone)
        val spanish = report.languages.single { it.language == "es" }
        assertEquals(3_000L, spanish.msPerWord)
        assertEquals(EstimateBasis.Language, spanish.estimateBasis)
        val catalan = report.languages.single { it.language == "ca" }
        assertNull(catalan.msPerWord)
        assertEquals(EstimateBasis.AllLanguages, catalan.estimateBasis)
        assertEquals(30_000L, catalan.totals.estimatedWritingMs)
        // A message with no language counts in the totals, not under a language.
        assertEquals(7, report.allTime.messages)
        assertEquals(2, report.languages.size)
    }

    // Sweep.

    @Test fun sweepsRecordsWhoseSessionIsGoneAfterTheGrace() {
        val records = listOf(
            chat("kept", 1, 1, at = now - day),
            chat("deleted", 1, 1, at = now - day),
            chat("just written", 1, 1, at = now - 1_000),
        )
        assertEquals(listOf("deleted"), TimeSweep.orphans(records, setOf("kept"), now))
    }
}
