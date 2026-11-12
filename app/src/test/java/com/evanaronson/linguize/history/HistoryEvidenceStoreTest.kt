package com.evanaronson.linguize.history

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.history.patterns.PatternFixtures.check
import com.evanaronson.linguize.history.patterns.PatternFixtures.fix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HistoryEvidenceStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val queries = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var queryHook: ((String) -> Unit)? = null
    private val store = SqliteHistoryStore(context, NAME, queryObserver = {
        queries += it
        queryHook?.invoke(it)
    })

    @After fun deleteDatabase() { context.deleteDatabase(NAME) }

    private suspend fun save(detail: SessionDetail) = store.close(detail.session, detail.suggestions)
    private suspend fun snapshot(limit: Int) = (store.evidence(limit) as HistoryEvidenceResult.Ready).snapshot

    @Test fun emptyHistoryIsASuccessfulCompleteWindowAtItsCommittedRevision() = runBlocking {
        val read = snapshot(400)
        assertEquals(0L, read.revision)
        assertEquals(HistoryEvidenceWindow(400, false), read.window)
        assertTrue(read.details.isEmpty())
        assertTrue(read.selectedIds.isEmpty())
    }

    @Test fun batchMatchesVisiblePagingAndDetailDecodingInExactlyTwoQueries() = runBlocking {
        val a = check("a", 2, fix("aqui", "aquí"), fix("tambien", "también"))
        val b = check("b", 2, fix("cafe", "café"))
        val chat = check("chat", 3, fix("esta", "está")).let {
            it.copy(session = it.session.copy(origin = Origin.Chat, finalText = it.session.text))
        }
        listOf(a, b, chat, check("older", 1)).forEach { save(it) }
        val hidden = check("hidden", 4)
        save(hidden.copy(session = hidden.session.copy(finalText = hidden.session.text)))
        save(check("copied", 5).let { it.copy(session = it.session.copy(outcome = Outcome.Copied)) })
        save(check("deleted", 6).let { it.copy(session = it.session.copy(deletedAt = 1)) })
        val open = check("open", 7).session.copy(closedAt = null, outcome = null, finalText = null)
        store.save(open)
        // Existing forward-compatible decoding skips unknown suggestions, while superseded
        // suggestions remain in detail evidence even though list summaries don't count them.
        raw {
            it.execSQL("UPDATE suggestions SET kind = 'future-kind' WHERE id = 'a-s0'")
            it.execSQL("UPDATE suggestions SET decision = 'superseded' WHERE id = 'a-s1'")
        }
        val ids = store.page(null, 3)!!.rows.map { it.id }
        val legacy = ids.map { (store.detail(it) as SessionDetailResult.Found).detail }
        assertEquals(listOf("chat", "b", "a"), ids)
        queries.clear()

        val read = snapshot(3)
        assertEquals(ids, read.selectedIds)
        assertEquals(legacy, read.details)
        assertEquals(HistoryEvidenceWindow(3, true), read.window)
        assertEquals(store.evidenceRevision(), read.revision)
        assertEquals(2, queries.count { it.contains("SELECT", ignoreCase = true) })
        assertEquals(Decision.Superseded, read.details.last().suggestions.single().decision)
    }

    @Test fun failedRelatedRowReadIsNotEmptyEvidenceAndCanRetry() = runBlocking {
        save(check("kept", 1, fix("aqui", "aquí")))
        val before = snapshot(400)
        raw { it.execSQL("ALTER TABLE suggestions RENAME TO unavailable_suggestions") }
        assertEquals(HistoryEvidenceResult.Failed, store.evidence(400))
        raw { it.execSQL("ALTER TABLE unavailable_suggestions RENAME TO suggestions") }
        assertEquals(before, snapshot(400))
    }

    @Test fun deletingChangesTheRevisionAndNewWindowWithoutMutatingTheOldSnapshot() = runBlocking {
        val first = check("first", 1, fix("aqui", "aquí"))
        val second = check("second", 2, fix("cafe", "café"))
        save(first)
        save(second)
        val before = snapshot(1)
        assertTrue(store.delete("second"))
        val after = snapshot(1)
        assertTrue(after.revision!! > before.revision!!)
        assertEquals(listOf(second), before.details)
        assertEquals(HistoryEvidenceWindow(1, true), before.window)
        assertEquals(listOf(first), after.details)
        assertEquals(HistoryEvidenceWindow(1, false), after.window)
    }

    @Test fun cancellationDuringTheReadPropagatesAndReleasesTheSnapshotLock() = runBlocking {
        save(check("kept", 1, fix("aqui", "aquí")))
        val read = async(start = CoroutineStart.LAZY) { store.evidence(400) }
        queryHook = { sql ->
            if (sql.contains("SELECT * FROM sessions")) {
                queryHook = null
                read.cancel(CancellationException("Superseded evidence"))
            }
        }
        read.start()
        read.join()
        assertTrue(read.isCancelled)
        assertEquals(listOf("kept"), snapshot(400).selectedIds)
    }

    @Test fun aCommittedWritePublishesItsRevisionBeforeACancelledDispatcherReturn() = runBlocking {
        val queued = ConcurrentLinkedQueue<Runnable>()
        val returning = CompletableDeferred<Unit>()
        val paused = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queued += block
                returning.complete(Unit)
            }
        }
        val before = store.evidenceRevision()
        val detail = check("committed", 1, fix("aqui", "aquí"))
        val writer = launch(paused, start = CoroutineStart.UNDISPATCHED) { save(detail) }
        try {
            withTimeout(5_000) { returning.await() }
            // IO has committed, but its caller's continuation has not run at all.
            assertTrue(store.evidenceRevision() > before)
        } finally {
            writer.cancel()
            while (true) (queued.poll() ?: break).run()
            writer.join()
        }
        val read = snapshot(400)
        assertEquals(listOf(detail), read.details)
        assertEquals(store.evidenceRevision(), read.revision)
    }

    @Test fun aWriteCannotSplitTheSessionAndSuggestionsOrChangeTheSnapshotsRevision() = runBlocking {
        val original = check("kept", 1, fix("aqui", "aquí"))
        save(original)
        val revision = store.evidenceRevision()
        val readingSuggestions = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        queryHook = { sql ->
            if (sql.contains("FROM suggestions") && sql.contains("\"sessionId\" IN")) {
                queryHook = null
                readingSuggestions.complete(Unit)
                kotlin.check(release.await(5, TimeUnit.SECONDS)) { "Evidence gate was not released" }
            }
        }
        val read = async { snapshot(400) }
        var writer: kotlinx.coroutines.Job? = null
        val changed = original.copy(
            session = original.session.copy(finalText = "new committed answer"),
            suggestions = original.suggestions.map { it.copy(toText = "new replacement") },
        )
        try {
            withTimeout(5_000) { readingSuggestions.await() }
            writer = launch(start = CoroutineStart.UNDISPATCHED) { save(changed) }
            assertFalse(writer.isCompleted)
            release.countDown()
            val before = read.await()
            writer.join()
            assertEquals(revision, before.revision)
            assertEquals(listOf(original), before.details)
            val after = snapshot(400)
            assertTrue(after.revision!! > revision)
            assertEquals(listOf(changed), after.details)
        } finally {
            release.countDown()
            read.cancel()
            writer?.cancel()
        }
    }

    private fun raw(block: (SQLiteDatabase) -> Unit) =
        SQLiteDatabase.openDatabase(context.getDatabasePath(NAME).path, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    private companion object { const val NAME = "history-evidence-test.db" }
}
