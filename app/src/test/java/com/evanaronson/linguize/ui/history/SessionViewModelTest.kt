package com.evanaronson.linguize.ui.history

import android.app.Application
import android.os.Looper
import com.evanaronson.linguize.data.Exports
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.history.Attempt
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SqliteHistoryStore
import com.evanaronson.linguize.history.SuggestionRecord
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
import org.junit.Assert.assertNull
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
    fun contradictoryOkAnswerKeepsItsLanguageWarningAndUnreadableAnswersUseSavedStatus() {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val history = object : HistoryStore by SqliteHistoryStore(context, "unused") {
            override suspend fun detail(id: String): SessionDetail {
                val raw = if (id == "contradictory") """{"status":"ok","language":"English"}""" else "not JSON"
                return detailOf(id).let { it.copy(session = it.session.copy(
                    requestedLanguage = "ca", status = if (id == "contradictory") Verdict.Status.Ok else Verdict.Status.WrongLanguage,
                    attempts = listOf(Attempt(2, raw = raw)),
                )) }
            }
        }
        val model = SessionViewModel(history, { Prompt("", "{}") }, AppLabels(context.packageManager), scope, {})
        try {
            listOf("contradictory", "unreadable").forEach { id ->
                model.load(id)
                waitFor { (model.page as? SessionPage.Shown)?.session?.id == id }
                val shown = model.page as SessionPage.Shown
                assertEquals(Verdict.Status.WrongLanguage, shown.warning)
                if (id == "contradictory") {
                    assertEquals(Verdict.Status.Ok, shown.session.status)
                    assertTrue(shown.card?.result is CheckResult.WrongLanguage)
                } else assertNull(shown.card)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun loadingDamagedAnswersPreservesExactTextAndRowsWithoutWriting() {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val row = SuggestionRecord("suggestion", "check", "device", createdAt = 1, updatedAt = 2,
            attempt = 0, kind = EditKind.Fix, start = -1, end = 999,
            fromText = "estas", toText = "estàs", why = "Recorded reason",
            decision = Decision.Accepted, decidedAt = 2)
        val promptReads = AtomicInteger()
        val before = "  Com estas?\n"
        val after = "  Com estàs?\n"
        val history = object : HistoryStore by SqliteHistoryStore(context, "unused") {
            override suspend fun detail(id: String): SessionDetail {
                val attempts = when (id) {
                    "missing" -> listOf(Attempt(2))
                    "unreadable" -> listOf(Attempt(2, raw = "not JSON"))
                    else -> listOf(Attempt(2), Attempt(3, raw = "{}"))
                }
                return detailOf(id).copy(session = detailOf(id).session.copy(
                    text = before, finalText = after, attempts = attempts), suggestions = listOf(if (id == "unreadable") row.copy(start = 6, end = 11) else row))
            }
            override suspend fun save(session: SessionRecord) = error("Viewing must not save")
            override suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>) = error("Viewing must not close")
            override suspend fun delete(id: String): Boolean = error("Viewing must not delete")
        }
        val model = SessionViewModel(history, { promptReads.incrementAndGet(); Prompt("", "{}") },
            AppLabels(context.packageManager), scope, {})
        try {
            listOf("missing", "unreadable", "later-answer").forEach { id ->
                model.load(id)
                waitFor { (model.page as? SessionPage.Shown)?.session?.id == id }
                val shown = model.page as SessionPage.Shown
                assertEquals(before, shown.session.text)
                assertEquals(after, shown.session.finalText)
                assertEquals(listOf(if (id == "unreadable") row.copy(start = 6, end = 11) else row), shown.suggestions)
                assertEquals(if (id == "missing") 0 else 1, promptReads.get())
                // No unrelated later Meaning, or failed replay, may hide the saved comparison.
                assertNull(shown.card)
            }
        } finally {
            scope.cancel()
        }
    }

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
