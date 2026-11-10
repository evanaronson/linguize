package com.evanaronson.linguize.history

import com.evanaronson.linguize.history.patterns.FakePatternHistory
import com.evanaronson.linguize.history.patterns.PatternFixtures.check
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryEvidenceReaderTest {
    private val details = (1..7).map { check("c$it", it) }.reversed()

    @Test
    fun smallerWindowsKeepSelectedRanksIncludingMissingRows() = runTest {
        val store = Store { limit ->
            HistoryEvidenceResult.Ready(HistoryEvidenceSnapshot(
                revision, HistoryEvidenceWindow(limit, true), details.drop(1), details.map { it.session.id },
            ))
        }
        val reader = HistoryEvidenceReader(store)
        reader.read(7)
        val smaller = reader.read(3).snapshot()
        assertEquals(listOf("c7", "c6", "c5"), smaller.selectedIds)
        assertEquals(listOf("c6", "c5"), smaller.details.map { it.session.id })
        assertEquals(HistoryEvidenceWindow(3, true), smaller.window)
        assertEquals(listOf(7), store.limits)
    }

    @Test
    fun exhaustedWindowSatisfiesLargerRequestsWithoutAnotherRead() = runTest {
        val store = Store { limit -> ready(limit, details.take(2), hasMore = false) }
        val reader = HistoryEvidenceReader(store)
        reader.read(400)
        val larger = reader.read(3000).snapshot()
        assertEquals(HistoryEvidenceWindow(3000, false), larger.window)
        assertEquals(2, larger.details.size)
        assertEquals(listOf(400), store.limits)
    }

    @Test
    fun growingWindowReadsOnlyUntilItHasEnoughCoverage() = runTest {
        val store = Store { limit -> ready(limit, details.take(limit), hasMore = limit < details.size) }
        val reader = HistoryEvidenceReader(store)
        reader.read(3)
        reader.read(5)
        assertEquals(2, reader.read(2).snapshot().details.size)
        reader.read(7)
        reader.read(5)
        assertEquals(listOf(3, 5, 7), store.limits)
    }

    @Test
    fun unknownRevisionIsNeverReusedAndDifferentStoresStaySeparate() = runTest {
        val first = Store { limit -> ready(limit, details.take(1)) }.apply { revision = null }
        val second = Store { limit -> ready(limit, details.takeLast(1)) }
        val firstReader = HistoryEvidenceReader(first)
        assertEquals("c7", firstReader.read(3).snapshot().details.single().session.id)
        assertEquals("c1", HistoryEvidenceReader(second).read(3).snapshot().details.single().session.id)
        firstReader.read(3)
        assertEquals(2, first.limits.size)
    }

    @Test
    fun failureDoesNotBecomeAnEmptySnapshotOrPreventRetry() = runTest {
        var fail = true
        val store = Store { limit -> if (fail) HistoryEvidenceResult.Failed else ready(limit, details) }
        val reader = HistoryEvidenceReader(store)
        assertEquals(HistoryEvidenceResult.Failed, reader.read(7))
        fail = false
        assertEquals(7, reader.read(7).snapshot().details.size)
        assertEquals(2, store.limits.size)
    }

    @Test
    fun deletionRevokesOldSnapshotEvenWhenReplacementReadFails() = runTest {
        var fail = false
        val store = Store { limit -> if (fail) HistoryEvidenceResult.Failed else ready(limit, details) }
        val reader = HistoryEvidenceReader(store)
        reader.read(7)
        store.revision = 1
        fail = true
        assertEquals(HistoryEvidenceResult.Failed, reader.read(3))
        fail = false
        assertEquals(1L, reader.read(3).snapshot().revision)
        assertEquals(3, store.limits.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun revisionWatcherReleasesCachedPayloadWithoutStartingAnotherRead() = runTest {
        val store = Store { limit -> ready(limit, details) }
        val reader = HistoryEvidenceReader(store)
        reader.read(7)
        // Inspect ownership directly: a weak reference/GC assertion would depend on timing.
        val cached = HistoryEvidenceReader::class.java.getDeclaredField("cached").apply { isAccessible = true }
        assertNotNull(cached.get(reader))

        backgroundScope.launch { reader.watch() }
        runCurrent()
        assertNotNull("Initial observation keeps the same revision", cached.get(reader))

        store.revision = 1
        runCurrent()
        assertNull("Deletion releases retained source text without a later consumer read", cached.get(reader))
        assertEquals("Invalidation does not eagerly reread History", listOf(7), store.limits)
    }

    @Test
    fun deletionDuringFillCannotPublishOldText() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = Store { limit ->
            val snapshot = ready(limit, if (revision == 0L) details else emptyList())
            if (revision == 0L) {
                started.complete(Unit)
                release.await()
            }
            snapshot
        }
        val reader = HistoryEvidenceReader(store)
        val pending = async { reader.read(7) }
        started.await()
        store.revision = 1
        release.complete(Unit)
        val result = pending.await().snapshot()
        assertEquals(1L, result.revision)
        assertTrue(result.details.isEmpty())
        assertEquals(2, store.limits.size)
    }

    @Test
    fun continuousMutationsFailExplicitlyInsteadOfLoopingOrPublishingStaleText() = runTest {
        val store = Store { limit ->
            val snapshot = ready(limit, details)
            revision = revision!! + 1
            snapshot
        }
        assertEquals(HistoryEvidenceResult.Failed, HistoryEvidenceReader(store).read(7))
        assertEquals(2, store.limits.size)
    }

    @Test
    fun overlappingReadersShareTheSuccessfulFill() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = Store { limit ->
            started.complete(Unit)
            release.await()
            ready(limit, details)
        }
        val reader = HistoryEvidenceReader(store)
        val first = async { reader.read(7) }
        started.await()
        val second = async { reader.read(3) }
        release.complete(Unit)
        assertEquals(7, first.await().snapshot().details.size)
        assertEquals(3, second.await().snapshot().details.size)
        assertEquals(listOf(7), store.limits)
    }

    @Test
    fun cancellationPropagatesAndDoesNotPoisonNextRead() = runTest {
        var cancel = true
        val store = Store { limit ->
            if (cancel) throw CancellationException("caller left")
            ready(limit, details)
        }
        val reader = HistoryEvidenceReader(store)
        var propagated = false
        try {
            reader.read(7)
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
        cancel = false
        assertFalse(reader.read(7).snapshot().details.isEmpty())
        assertEquals(2, store.limits.size)
    }

    private class Store(
        val read: suspend Store.(Int) -> HistoryEvidenceResult,
    ) : HistoryStore by FakePatternHistory() {
        private val committed = MutableStateFlow(0L)
        var revision: Long? = 0
            set(value) {
                field = value
                if (value != null) committed.value = value
            }
        val limits = mutableListOf<Int>()
        override fun revisions(): Flow<Long> = committed
        override fun evidenceRevision(): Long? = revision
        override suspend fun evidence(limit: Int): HistoryEvidenceResult {
            limits += limit
            return read(limit)
        }
        fun ready(limit: Int, details: List<SessionDetail>, hasMore: Boolean = false) =
            HistoryEvidenceResult.Ready(HistoryEvidenceSnapshot(revision, HistoryEvidenceWindow(limit, hasMore), details))
    }

    private fun HistoryEvidenceResult.snapshot() = (this as HistoryEvidenceResult.Ready).snapshot
}
