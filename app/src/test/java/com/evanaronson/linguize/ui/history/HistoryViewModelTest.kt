package com.evanaronson.linguize.ui.history

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.SessionSummary
import com.evanaronson.linguize.history.SqliteHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class HistoryViewModelTest {
    private val app = RuntimeEnvironment.getApplication<App>()
    private val rows = MutableStateFlow((0 until 30).map {
        SessionSummary("$it", 100L - it, null, null, "Text $it", null, 0, 0, 0)
    })
    private val limits = CopyOnWriteArrayList<Int>()
    private val deleted = AtomicInteger()
    private val history = object : HistoryStore by SqliteHistoryStore(app, "unused") {
        override fun recent(limit: Int) = rows.map { it.take(limit) }.also { limits.add(limit) }
        override suspend fun delete(id: String): Boolean {
            rows.value = rows.value.filterNot { it.id == id }
            deleted.incrementAndGet()
            return true
        }
    }
    private val models = ViewModelStore()
    private val model = ViewModelProvider(models, viewModelFactory {
        initializer { HistoryViewModel(app, history) }
    })[HistoryViewModel::class.java]
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun close() {
        models.clear()
        scope.cancel()
    }

    @Test
    fun successfulDeletionsKeepThePreviewQueryBounded() {
        scope.launch { model.recent.collect() }
        waitFor { model.recent.value?.size == 6 }
        repeat(12) { index ->
            val id = model.recent.value!!.first().summary.id
            model.delete(id)
            model.undoShown(id)
            waitFor { limits.lastOrNull() == 7 && model.recent.value?.firstOrNull()?.summary?.id != id }
            model.commitDelete(id)
            // Both the in-memory tombstone and its inflated query must retire after ack.
            waitFor {
                deleted.get() == index + 1 && limits.lastOrNull() == 6 &&
                    model.recent.value?.firstOrNull()?.summary?.id == "${index + 1}"
            }
            assertEquals(6, model.recent.value!!.size)
        }
        assertEquals(6, limits.last())
    }

    @Test
    fun undoRestoresTheRowWithoutDeletingItOrKeepingAnInflatedQuery() {
        scope.launch { model.recent.collect() }
        waitFor { model.recent.value?.size == 6 }
        model.delete("0")
        model.undoShown("0")
        waitFor { model.recent.value?.firstOrNull()?.summary?.id == "1" }
        model.undoDelete("0")
        waitFor { limits.lastOrNull() == 6 && model.recent.value?.firstOrNull()?.summary?.id == "0" }
        assertEquals(0, deleted.get())
        assertEquals(30, rows.value.size)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for history")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }
}
