package com.evanaronson.linguize.ui.history

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.SessionSummary
import com.evanaronson.linguize.history.SqliteHistoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class HistoryViewModelTest {
    private val app = RuntimeEnvironment.getApplication() as App
    private val rows = MutableStateFlow((0 until 30).map {
        SessionSummary("$it", 100L - it, null, null, "Text $it", null, 0, 0, 0)
    })
    private val limits = CopyOnWriteArrayList<Int>()
    private val deleted = AtomicInteger()
    @Volatile private var deleteOperation: suspend (String) -> Boolean = { id ->
        rows.update { rows -> rows.filterNot { it.id == id } }
        true
    }
    private val history = object : HistoryStore by SqliteHistoryStore(app, "unused") {
        override fun recent(limit: Int) = rows.map { it.take(limit) }.also { limits.add(limit) }
        override suspend fun delete(id: String): Boolean {
            deleted.incrementAndGet()
            return deleteOperation(id)
        }
    }
    private val models = ViewModelStore()
    private val model by lazy {
        ViewModelProvider(models, viewModelFactory {
            initializer { HistoryViewModel(app, history) }
        })[HistoryViewModel::class.java]
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun close() {
        models.clear()
        scope.cancel()
    }

    @Test
    fun successfulDeletionsKeepThePreviewQueryBounded() {
        observeRecent()
        waitFor { model.recent.value?.size == 5 }
        repeat(12) { index ->
            val id = model.recent.value!!.first().summary.id
            model.delete(id)
            model.undoShown(id)
            waitFor { limits.lastOrNull() == 6 && model.recent.value?.firstOrNull()?.summary?.id != id }
            model.commitDelete(id)
            // Both the in-memory tombstone and its inflated query must retire after ack.
            waitFor {
                deleted.get() == index + 1 && limits.lastOrNull() == 5 &&
                    model.recent.value?.firstOrNull()?.summary?.id == "${index + 1}"
            }
            assertEquals(5, model.recent.value!!.size)
        }
        assertEquals(5, limits.last())
    }

    @Test
    fun undoRestoresTheRowWithoutDeletingItOrKeepingAnInflatedQuery() {
        observeRecent()
        waitFor { model.recent.value?.size == 5 }
        model.delete("0")
        model.undoShown("0")
        waitFor { model.recent.value?.firstOrNull()?.summary?.id == "1" }
        model.undoDelete("0")
        waitFor { limits.lastOrNull() == 5 && model.recent.value?.firstOrNull()?.summary?.id == "0" }
        assertEquals(0, deleted.get())
        assertEquals(30, rows.value.size)
    }

    @Test
    fun failedTransactionStaysHiddenAndDurableUntilRetrySucceeds() {
        deleteOperation = { false }
        val export = runBlocking {
            app.exports.write(LocalDate.of(2026, 10, 16)) {
                it.write("private history".toByteArray())
                true
            }!!
        }
        observeRecent()
        waitFor { model.recent.value != null }
        val previewLimit = limits.last()
        model.delete("0")
        model.commitDelete("0")
        waitFor { model.failedDeletes.value == setOf("0") && !export.exists() }
        assertNull(model.pendingDelete.value)
        assertTrue(rows.value.any { it.id == "0" })
        assertEquals(setOf("0"), deletionNotes())
        waitFor { model.recent.value?.firstOrNull()?.summary?.id == "1" }
        assertFalse(app.exports.isCurrent(export))

        deleteOperation = { id ->
            rows.update { rows -> rows.filterNot { it.id == id } }
            true
        }
        model.retryFailedDeletes()
        waitFor { deleted.get() == 2 && deletionNotes().isEmpty() && limits.lastOrNull() == previewLimit }
        assertTrue(model.failedDeletes.value.isEmpty())
        assertNull(model.pendingDelete.value)
        assertFalse(rows.value.any { it.id == "0" })
    }

    @Test
    fun failedCleanupDoesNotRestoreAnAlreadyDeletedRowAndCanBeRetried() {
        deleteOperation = { id ->
            rows.update { rows -> rows.filterNot { it.id == id } }
            false
        }
        observeRecent()
        waitFor { model.recent.value != null }
        val previewLimit = limits.last()
        model.delete("0")
        model.commitDelete("0")
        waitFor { model.failedDeletes.value == setOf("0") && limits.lastOrNull() == previewLimit + 1 &&
            model.recent.value?.firstOrNull()?.summary?.id == "1" }
        assertFalse(rows.value.any { it.id == "0" })
        assertEquals(setOf("0"), deletionNotes())
        assertEquals("1", model.recent.value!!.first().summary.id)

        deleteOperation = { true }
        model.retryFailedDeletes()
        waitFor { deleted.get() == 2 && deletionNotes().isEmpty() && limits.lastOrNull() == previewLimit }
        assertTrue(model.failedDeletes.value.isEmpty())
        assertNull(model.pendingDelete.value)
        assertEquals("1", model.recent.value!!.first().summary.id)
    }

    @Test
    fun recoveredFailureCanBeRetriedInTheSameProcessWithoutUndo() {
        val prefs = app.getSharedPreferences("recent", Context.MODE_PRIVATE)
        prefs.edit().putString("pendingProcess", "earlier-process")
            .putString("pendingOperation:earlier-owner:0", "0").commit()
        deleteOperation = { false }
        waitFor { model.failedDeletes.value == setOf("0") }
        assertNull(model.pendingDelete.value)
        assertEquals(setOf("0"), deletionNotes())

        deleteOperation = { id ->
            rows.update { rows -> rows.filterNot { it.id == id } }
            true
        }
        model.retryFailedDeletes()
        waitFor { deleted.get() == 2 && deletionNotes().isEmpty() }
        assertTrue(model.failedDeletes.value.isEmpty())
        assertNull(model.pendingDelete.value)
    }

    @Test
    fun retryCoversEveryFailureAndRetainsOnlyWorkThatStillFails() {
        deleteOperation = { false }
        model.delete("0")
        model.delete("1")
        model.commitDelete("1")
        waitFor { model.failedDeletes.value == setOf("0", "1") }
        assertEquals(setOf("0", "1"), deletionNotes())

        deleteOperation = { id ->
            if (id == "0") {
                rows.update { rows -> rows.filterNot { it.id == id } }
                true
            } else false
        }
        model.retryFailedDeletes()
        waitFor { deleted.get() == 4 && model.failedDeletes.value == setOf("1") && deletionNotes() == setOf("1") }
        assertNull(model.pendingDelete.value)

        deleteOperation = { true }
        model.retryFailedDeletes()
        waitFor { deleted.get() == 5 && deletionNotes().isEmpty() }
        assertTrue(model.failedDeletes.value.isEmpty())
    }

    @Test
    fun retryCompletionPreservesANewerPendingUndoAndItsNote() {
        deleteOperation = { false }
        observeRecent()
        waitFor { model.recent.value != null }
        model.delete("0")
        model.commitDelete("0")
        waitFor { model.failedDeletes.value == setOf("0") }

        val finish = CompletableDeferred<Unit>()
        deleteOperation = { id ->
            finish.await()
            rows.update { rows -> rows.filterNot { it.id == id } }
            true
        }
        model.retryFailedDeletes()
        waitFor { deleted.get() == 2 }
        model.delete("1")
        model.undoShown("1")
        finish.complete(Unit)
        waitFor { deletionNotes() == setOf("1") && model.recent.value?.firstOrNull()?.summary?.id == "2" }
        assertEquals("1", model.pendingDelete.value)
        assertTrue(model.failedDeletes.value.isEmpty())

        model.undoDelete("1")
        waitFor { deletionNotes().isEmpty() && model.recent.value?.firstOrNull()?.summary?.id == "1" }
        assertEquals(2, deleted.get())
        assertTrue(rows.value.any { it.id == "1" })
    }

    private fun observeRecent() {
        val recent = model.recent
        scope.launch { recent.collect() }
    }

    private fun deletionNotes(): Set<String> = app.getSharedPreferences("recent", Context.MODE_PRIVATE)
        .all.filterKeys { it.startsWith("pendingOperation:") }.values.filterIsInstance<String>().toSet()

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for history")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }
}
