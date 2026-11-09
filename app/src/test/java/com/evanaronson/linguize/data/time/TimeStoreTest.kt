package com.evanaronson.linguize.data.time

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.time.TimeRecord
import com.evanaronson.linguize.core.time.TimeSweep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TimeStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = TimeStore(context, scope)

    @After fun close() {
        scope.cancel()
        store.close()
    }

    private fun record(id: String, at: Long = 1_000, writing: Long? = null) =
        TimeRecord(id, "menu", "es", writing, 5_000, 7, 2, 1, at, messageId = if (writing != null) "m-$id" else null)

    @Test fun savesAndReadsEveryField() = runBlocking {
        val chat = record("s1", writing = 12_000).copy(origin = "chat")
        val menu = record("s2", at = 2_000).copy(language = null)
        store.save(chat)
        store.save(menu)
        assertEquals(listOf(chat, menu), store.all())
    }

    @Test fun aLaterRecordForTheSameSessionReplacesIt() = runBlocking {
        store.save(record("s1"))
        store.save(record("s1").copy(reviewMs = 9_000))
        assertEquals(9_000L, store.all().single().reviewMs)
    }

    @Test fun deletingConversationsSessionsDeletesTheirRows() = runBlocking {
        listOf("s1", "s2", "s3").forEach { store.save(record(it)) }
        assertEquals(2, store.delete(listOf("s1", "s3")))
        assertEquals(listOf("s2"), store.all().map { it.sessionId })
    }

    @Test fun sweepDropsRowsWhoseHistorySessionIsGone() = runBlocking {
        val now = 10 * TimeSweep.GRACE_MS
        store.save(record("kept"))
        store.save(record("deleted from history"))
        store.save(record("still being written", at = now - 1_000))
        assertEquals(1, store.sweep(setOf("kept"), now))
        assertEquals(setOf("kept", "still being written"), store.all().map { it.sessionId }.toSet())
        // History cleared: everything old goes.
        assertEquals(1, store.sweep(emptySet(), now))
        assertEquals(listOf("still being written"), store.all().map { it.sessionId })
    }

    @Test fun recordWritesInTheBackground() {
        store.record(record("s1"))
        val deadline = System.currentTimeMillis() + 5_000
        while (runBlocking { store.all() }.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(runBlocking { store.all() }.isNotEmpty())
    }
}
