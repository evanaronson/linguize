package com.evanaronson.linguize

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.data.chat.PendingChatDeletion
import com.evanaronson.linguize.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Real SQLite transactions and restart, with acknowledged/gated child cleanup. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatDeletionsTest {
    @get:Rule val main = MainDispatcherRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "chat-deletion-contracts"
    private var store = openStore()

    @After fun close() { store.close(); context.deleteDatabase(name) }

    private fun openStore() = ChatStore(context, name, main.dispatcher)
    private fun database(block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    }

    private suspend fun seed(id: String = "conversation", sessions: List<String> = listOf("s1", "s2")) {
        assertTrue(store.create(Conversation(id, "Title", "es", 1, 1)))
        sessions.forEachIndexed { index, session ->
            assertTrue(store.add(ChatMessage("$id-$index", id, true, "Hola", "Hola", at = 2L + index, sessionId = session)))
        }
    }

    @Test fun failureToPersistCleanupRollsBackConversationAndMessageDeletion() = runTest(main.dispatcher) {
        seed()
        database { it.execSQL("CREATE TRIGGER reject_cleanup BEFORE INSERT ON pending_deletions BEGIN SELECT RAISE(ABORT, 'blocked cleanup'); END") }

        assertFalse(store.delete("conversation"))

        assertNotNull(store.conversation("conversation"))
        assertEquals(listOf("s1", "s2"), store.messages("conversation").map { it.sessionId })
        assertTrue(store.pendingDeletions().entries.isEmpty())
    }

    @Test fun deletedConversationKeepsItsCleanupAcrossRestartAndHistoryFailure() = runTest(main.dispatcher) {
        seed()
        seed("other", listOf("untouched"))
        assertTrue(store.delete("conversation"))
        store.close()
        store = openStore()
        assertNull(store.conversation("conversation"))
        assertEquals(listOf(PendingChatDeletion("conversation", listOf("s1", "s2"))), store.pendingDeletions().entries)

        val operations = mutableListOf<String>()
        var historyWorks = false
        val cleanup = ChatDeletions(store,
            forgetMemory = { operations += "memory:$it" },
            deleteHistory = { operations += "history:$it"; historyWorks || it != "s2" },
            deleteTiming = { operations += "time:${it.joinToString()}"; true },
            forgetExports = { operations += "exports"; true },
        )
        assertFalse(cleanup.drain())
        assertEquals("memory:chat:conversation", operations.first())
        assertTrue("time:s1, s2" in operations)
        assertTrue("exports" in operations)
        assertEquals(1, store.pendingDeletions().entries.size)

        historyWorks = true
        assertTrue(cleanup.drain())
        assertTrue(store.pendingDeletions().entries.isEmpty())
        assertEquals(2, operations.count { it == "history:s1" }) // Completed children can be retried safely.
        assertEquals(listOf("untouched"), store.sessionIds("other"))
    }

    @Test fun anUnreadableMessageStillContributesItsRawHistoryId() = runTest(main.dispatcher) {
        seed(sessions = listOf("linked-to-unreadable-row"))
        database { it.execSQL("UPDATE messages SET changes = 'not JSON'") }
        assertTrue(store.messages("conversation").isEmpty())

        assertTrue(store.delete("conversation"))

        assertEquals(listOf("linked-to-unreadable-row"), store.pendingDeletions().entries.single().sessionIds)
        val history = mutableListOf<String>()
        assertTrue(ChatDeletions(store, {}, { history += it; true }, { true }).drain())
        assertEquals(listOf("linked-to-unreadable-row"), history)
    }

    @Test fun aVersionThreeDatabaseGainsTheQueueWithoutChangingSavedMessages() = runTest(main.dispatcher) {
        seed()
        store.close()
        database { it.execSQL("DROP TABLE pending_deletions"); it.version = 3 }
        store = openStore()

        assertEquals(listOf("s1", "s2"), store.messages("conversation").map { it.sessionId })
        assertTrue(store.pendingDeletions().entries.isEmpty())
        assertTrue(store.delete("conversation"))
        assertEquals(listOf("s1", "s2"), store.pendingDeletions().entries.single().sessionIds)
    }

    @Test fun malformedPendingWorkSurvivesRestartWithoutBlockingValidCleanup() = runTest(main.dispatcher) {
        seed("malformed", listOf("unreadable-session"))
        seed("valid", listOf("valid-session"))
        assertTrue(store.delete("malformed"))
        assertTrue(store.delete("valid"))
        database { it.execSQL("UPDATE pending_deletions SET session_ids = 'not JSON' WHERE conversation = 'malformed'") }
        val memory = mutableListOf<String>()
        val history = mutableListOf<String>()
        val cleanup = ChatDeletions(store, { memory += it }, { history += it; true }, { true })

        assertFalse(cleanup.drain())
        assertEquals(listOf("chat:valid"), memory)
        assertEquals(listOf("valid-session"), history)
        assertTrue(store.pendingDeletions().hasUnreadableEntries)
        assertTrue(store.pendingDeletions().entries.isEmpty())

        store.close()
        store = openStore()
        assertTrue(store.pendingDeletions().hasUnreadableEntries)
        assertFalse(ChatDeletions(store, {}, { true }, { true }).drain())
        database { db ->
            db.rawQuery("SELECT conversation, session_ids FROM pending_deletions", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("malformed", it.getString(0))
                assertEquals("not JSON", it.getString(1))
                assertFalse(it.moveToNext())
            }
        }
    }

    @Test fun aFailedQueueReadCannotAcknowledgeAnEmptyQueue() = runTest(main.dispatcher) {
        seed()
        store.delete("conversation")
        database { it.execSQL("DROP TABLE pending_deletions") }
        var calls = 0

        assertFalse(ChatDeletions(store, { calls++ }, { calls++; true }, { calls++; true }).drain())
        assertEquals(0, calls)
    }

    @Test fun independentCleanupContinuesAfterMemoryFailureAndRetriesAllUnacknowledgedWork() = runTest(main.dispatcher) {
        seed()
        store.delete("conversation")
        var works = false
        val history = mutableListOf<String>()
        var timing = 0
        var exports = 0
        val cleanup = ChatDeletions(store,
            forgetMemory = { if (!works) throw IOException("memory disk unavailable") },
            deleteHistory = { history += it; true },
            deleteTiming = { timing++; works },
            forgetExports = { exports++; works },
        )
        assertFalse(cleanup.drain())
        assertEquals(listOf("s1", "s2"), history)
        assertEquals(1, timing)
        assertEquals(1, exports)
        assertEquals(1, store.pendingDeletions().entries.size)

        works = true
        assertTrue(cleanup.drain())
        assertTrue(store.pendingDeletions().entries.isEmpty())
    }

    @Test fun cancellationLeavesDurableWorkAndReleasesTheDrainLock() = runTest(main.dispatcher) {
        seed()
        store.delete("conversation")
        val started = CompletableDeferred<Unit>()
        var wait = true
        var historyCalls = 0
        val cleanup = ChatDeletions(store,
            forgetMemory = { if (wait) { started.complete(Unit); awaitCancellation() } },
            deleteHistory = { historyCalls++; true },
            deleteTiming = { true },
        )
        val draining = launch { cleanup.drain() }
        runCurrent()
        assertTrue(started.isCompleted)
        draining.cancelAndJoin()
        assertEquals(0, historyCalls)
        assertEquals(1, store.pendingDeletions().entries.size)

        wait = false
        assertTrue(cleanup.drain())
        assertTrue(store.pendingDeletions().entries.isEmpty())
    }

    @Test fun failedQueueRetirementKeepsWorkForAnIdempotentRetry() = runTest(main.dispatcher) {
        seed()
        store.delete("conversation")
        database { it.execSQL("CREATE TRIGGER reject_retirement BEFORE DELETE ON pending_deletions BEGIN SELECT RAISE(ABORT, 'blocked retirement'); END") }
        val cleanup = ChatDeletions(store, {}, { true }, { true })

        assertFalse(cleanup.drain())
        assertEquals(1, store.pendingDeletions().entries.size)
        database { it.execSQL("DROP TRIGGER reject_retirement") }
        assertTrue(cleanup.drain())
        assertTrue(store.pendingDeletions().entries.isEmpty())
    }
}
