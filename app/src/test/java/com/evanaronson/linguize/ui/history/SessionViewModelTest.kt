package com.evanaronson.linguize.ui.history

import android.app.Application
import android.os.Looper
import com.evanaronson.linguize.data.Exports
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SqliteHistoryStore
import com.evanaronson.linguize.llm.Prompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionViewModelTest {
    @get:Rule val cache = TemporaryFolder()

    @Test
    fun failedDeletionKeepsTheCheckVisibleAndCanBeRetried() {
        val context = RuntimeEnvironment.getApplication()
        val firstWrite = CompletableDeferred<Boolean>()
        val secondWrite = CompletableDeferred<Boolean>()
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val history = object : HistoryStore by SqliteHistoryStore(context, "unused") {
            override suspend fun detail(id: String) = detailOf(id)
            override suspend fun delete(id: String): Boolean =
                if (calls.incrementAndGet() == 1) firstWrite.await() else secondWrite.await()
        }
        val model = SessionViewModel(history, { Prompt("", "{}") }, AppLabels(context.packageManager), scope, {})
        try {
            model.load("check")
            waitFor { model.page is SessionPage.Shown }
            val shown = model.page
            model.delete()
            waitFor { calls.get() == 1 }
            assertEquals(SessionDeletion.Deleting, model.deletion)
            assertSame(shown, model.page)
            model.delete() // A second tap cannot start another write.
            firstWrite.complete(false)
            waitFor { model.deletion == SessionDeletion.Failed }
            assertEquals(1, calls.get())
            assertSame(shown, model.page)

            model.delete()
            waitFor { calls.get() == 2 }
            assertSame(shown, model.page)
            secondWrite.complete(true)
            waitFor { model.deletion == SessionDeletion.Deleted }
            assertSame(SessionPage.Missing, model.page)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aDeletionFinishingAfterNavigationCannotCloseAnotherCheck() {
        val context = RuntimeEnvironment.getApplication()
        val write = CompletableDeferred<Boolean>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val history = object : HistoryStore by SqliteHistoryStore(context, "unused") {
            override suspend fun detail(id: String) = detailOf(id)
            override suspend fun delete(id: String): Boolean = write.await()
        }
        val model = SessionViewModel(history, { Prompt("", "{}") }, AppLabels(context.packageManager), scope, {})
        try {
            model.load("first")
            waitFor { model.page is SessionPage.Shown }
            model.delete()
            model.load("second")
            waitFor { (model.page as? SessionPage.Shown)?.session?.id == "second" }
            write.complete(true)
            waitFor { scope.coroutineContext[Job]!!.children.none { it.isActive } }
            assertEquals("second", (model.page as SessionPage.Shown).session.id)
            assertEquals(SessionDeletion.Ready, model.deletion)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun deletionRetiresAnExportRecreatedWhileTheDatabaseDeleteWasWaiting() = runBlocking {
        withTimeout(5_000) {
            val context = RuntimeEnvironment.getApplication()
            val deletionStarted = CompletableDeferred<Unit>()
            val finishDeletion = CompletableDeferred<Unit>()
            val retired = CompletableDeferred<Unit>()
            val exports = Exports(cache.root)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val history = object : HistoryStore by SqliteHistoryStore(context, "unused") {
                override suspend fun detail(id: String): SessionDetail? = null
                override suspend fun delete(id: String): Boolean {
                    deletionStarted.complete(Unit)
                    finishDeletion.await()
                    return true
                }
            }
            val model = SessionViewModel(history, prompt = { error("No replay needed") },
                AppLabels(context.packageManager), scope, retireExports = {
                    exports.clear()
                    retired.complete(Unit)
                })
            try {
                model.load("deleted-session")
                model.delete()
                deletionStarted.await()
                assertFalse(retired.isCompleted)
                // Another settings host exported while the row still existed in storage.
                val file = exports.write(LocalDate.now()) {
                    it.write("deleted text".toByteArray())
                    true
                }!!
                assertTrue(file.exists())
                finishDeletion.complete(Unit)
                retired.await()
                assertFalse(file.exists())
                assertFalse(exports.isCurrent(file))
            } finally {
                finishDeletion.complete(Unit)
                scope.cancel()
            }
        }
    }

    private fun detailOf(id: String) = SessionDetail(SessionRecord(
        id = id, deviceId = "device", createdAt = 1, updatedAt = 2, startedAt = 1, closedAt = 2,
        origin = Origin.Menu, text = "Com estas?", textHash = "hash", punctuation = "moderate",
        judgments = "both", provider = "gemini", model = "model", promptHash = "prompt", appVersion = "1",
    ), emptyList())

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for saved check state")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }
}
