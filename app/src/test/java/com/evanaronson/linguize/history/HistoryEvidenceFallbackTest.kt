package com.evanaronson.linguize.history

import com.evanaronson.linguize.history.patterns.FakePatternHistory
import com.evanaronson.linguize.history.patterns.PatternFixtures.check
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class HistoryEvidenceFallbackTest {
    private val history = FakePatternHistory(listOf(check("older", 0), check("a", 1), check("b", 2)))

    @Test fun aRemovedDetailKeepsItsRankedWindowSlotWithoutBecomingAReadFailure() = runBlocking {
        history.vanishOnDetail = "b"
        val read = (history.evidence(2) as HistoryEvidenceResult.Ready).snapshot
        assertNull(read.revision)
        assertNull(history.evidenceRevision())
        assertEquals(HistoryEvidenceWindow(2, true), read.window)
        assertEquals(listOf("b", "a"), read.selectedIds)
        assertEquals(listOf("a"), read.details.map { it.session.id })
        assertEquals(listOf("b", "a"), history.detailReads)
    }

    @Test fun aFailedPageOrDetailFailsTheWholeWindowAndCanRetry() = runBlocking {
        history.failPage = true
        assertEquals(HistoryEvidenceResult.Failed, history.evidence(400))
        assertTrue(history.detailReads.isEmpty())
        history.failPage = false
        history.failDetail = "a"
        assertEquals(HistoryEvidenceResult.Failed, history.evidence(400))
        history.failDetail = null
        val read = (history.evidence(400) as HistoryEvidenceResult.Ready).snapshot
        assertEquals(listOf("b", "a", "older"), read.selectedIds)
        assertEquals(HistoryEvidenceWindow(400, false), read.window)
    }

    @Test fun anAdapterExceptionDoesNotBecomeEmptyEvidence() = runBlocking {
        val broken = object : HistoryStore by history {
            override suspend fun evidence(limit: Int) = readEvidencePages(limit)
            override suspend fun page(before: HistoryCursor?, limit: Int): HistoryPage? = throw IOException("Read failed")
        }
        assertEquals(HistoryEvidenceResult.Failed, broken.evidence(400))
        history.clear()
        val empty = (history.evidence(400) as HistoryEvidenceResult.Ready).snapshot
        assertTrue(empty.details.isEmpty())
        assertTrue(empty.selectedIds.isEmpty())
        assertEquals(HistoryEvidenceWindow(400, false), empty.window)
    }

    @Test fun adapterCancellationPropagatesInsteadOfReturningFailed() = runBlocking {
        val cancelled = object : HistoryStore by history {
            override suspend fun evidence(limit: Int) = readEvidencePages(limit)
            override suspend fun page(before: HistoryCursor?, limit: Int): HistoryPage? = throw CancellationException("Superseded")
        }
        try {
            cancelled.evidence(400)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(history.detailReads.isEmpty())
        }
    }
}
