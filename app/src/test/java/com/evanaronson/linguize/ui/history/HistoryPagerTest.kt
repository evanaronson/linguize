package com.evanaronson.linguize.ui.history

import com.evanaronson.linguize.history.HistoryCursor
import com.evanaronson.linguize.history.HistoryPage
import com.evanaronson.linguize.history.SessionSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPagerTest {
    private data class Request(val cursor: HistoryCursor?, val limit: Int,
        val answer: CompletableDeferred<HistoryPage?> = CompletableDeferred(),
        val returned: CompletableDeferred<Unit> = CompletableDeferred())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val revisions = MutableStateFlow(0L)
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val pager = HistoryPager(scope, revisions, { cursor, limit ->
        val request = Request(cursor, limit)
        requests.send(request)
        // Exercise even a loader that is slow to cooperate with cancellation.
        withContext(NonCancellable) { request.answer.await().also { request.returned.complete(Unit) } }
    })

    @After fun close() { scope.cancel() }

    @Test
    fun requestsOnePageAtATimeAndStopsAtTheEnd() = runBlocking {
        withTimeout(5_000) {
            pager.start()
            val first = requests.receive()
            assertNull(first.cursor)
            assertEquals(25, first.limit)
            pager.loadMore()
            assertTrue(requests.tryReceive().isFailure)
            val cursor = HistoryCursor(75, "24")
            first.answer.complete(HistoryPage(rows(0, 25), cursor))
            pager.state.first { !it.loading }
            pager.loadMore()
            pager.loadMore()
            val second = requests.receive()
            assertEquals(cursor, second.cursor)
            assertEquals(25, second.limit)
            assertTrue(requests.tryReceive().isFailure)
            second.answer.complete(HistoryPage(rows(25, 2), null))
            val done = pager.state.first { !it.loading }
            assertEquals(27, done.rows.size)
            assertNull(done.next)
            pager.loadMore()
            assertTrue(requests.tryReceive().isFailure)
        }
    }

    @Test
    fun aFailedAppendKeepsTheRowsAndRetriesTheSameCursor() = runBlocking {
        withTimeout(5_000) {
            pager.start()
            val cursor = HistoryCursor(100, "0")
            requests.receive().answer.complete(HistoryPage(rows(0, 1), cursor))
            pager.state.first { !it.loading }
            pager.loadMore()
            requests.receive().answer.complete(null)
            val failed = pager.state.first { it.failed }
            assertEquals(listOf("0"), failed.rows.map { it.summary.id })
            pager.retry()
            val retry = requests.receive()
            assertEquals(cursor, retry.cursor)
            retry.answer.complete(HistoryPage(rows(1, 1), null))
            val done = pager.state.first { !it.loading }
            assertFalse(done.failed)
            assertEquals(listOf("0", "1"), done.rows.map { it.summary.id })
        }
    }

    @Test
    fun clearInvalidatesAnOlderPageEvenWhenItsReadFinishesLate() = runBlocking {
        withTimeout(5_000) {
            pager.start()
            requests.receive().answer.complete(HistoryPage(rows(0, 1), HistoryCursor(100, "0")))
            pager.state.first { !it.loading }
            pager.loadMore()
            val obsolete = requests.receive()
            revisions.value++
            val refresh = requests.receive()
            assertNull(refresh.cursor)
            refresh.answer.complete(HistoryPage(emptyList(), null))
            pager.state.first { !it.loading && it.rows.isEmpty() }
            obsolete.answer.complete(HistoryPage(rows(1, 1), null))
            obsolete.returned.await()
            yield()
            assertTrue(pager.state.value.rows.isEmpty())
            assertNull(pager.state.value.next)
        }
    }

    @Test
    fun retiringADeletedIdCannotExposeItFromALatePage() = runBlocking {
        withTimeout(5_000) {
            pager.start()
            requests.receive().answer.complete(HistoryPage(rows(0, 2), HistoryCursor(99, "1")))
            pager.state.first { !it.loading }
            pager.loadMore()
            val obsolete = requests.receive()
            pager.remove("0")
            assertEquals(listOf("1"), pager.state.value.rows.map { it.summary.id })
            val refresh = requests.receive()
            assertNull(refresh.cursor)
            refresh.answer.complete(HistoryPage(rows(1, 2), null))
            pager.state.first { !it.loading }
            obsolete.answer.complete(HistoryPage(rows(0, 1), null))
            obsolete.returned.await()
            yield()
            assertEquals(listOf("1", "2"), pager.state.value.rows.map { it.summary.id })
        }
    }

    @Test
    fun aRefreshKeepsTheLoadedWindowAndARestoredPositionLoadsEnoughRows() = runBlocking {
        withTimeout(5_000) {
            pager.start(minimumRows = 80)
            val first = requests.receive()
            assertEquals(80, first.limit)
            first.answer.complete(HistoryPage(rows(0, 80), HistoryCursor(21, "79")))
            pager.state.first { !it.loading }
            pager.start()
            assertTrue(requests.tryReceive().isFailure)
            revisions.value++
            val refresh = requests.receive()
            assertEquals(80, refresh.limit)
            assertEquals(80, pager.state.value.rows.size)
            refresh.answer.complete(HistoryPage(rows(0, 80), null))
            assertEquals(80, pager.state.first { !it.loading }.rows.size)
        }
    }

    private fun rows(start: Int, count: Int) = (start until start + count).map {
        SessionSummary(it.toString(), 100L - it, null, null, "Text $it", null, 0, 0, 0)
    }
}
