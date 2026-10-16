package com.evanaronson.linguize.ui.history

import android.app.Application
import android.os.Looper
import com.evanaronson.linguize.data.Exports
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.interpret
import com.evanaronson.linguize.history.Attempt
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.Opening
import com.evanaronson.linguize.history.SessionContext
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionDetailResult
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SessionRecording
import com.evanaronson.linguize.history.SessionSettings
import com.evanaronson.linguize.history.SqliteHistoryStore
import com.evanaronson.linguize.history.SuggestionRecord
import com.evanaronson.linguize.llm.Prompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
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
            override suspend fun detail(id: String): SessionDetailResult {
                val raw = if (id == "contradictory") """{"status":"ok","language":"English"}""" else "not JSON"
                return SessionDetailResult.Found(detailOf(id).let { it.copy(session = it.session.copy(
                    requestedLanguage = "ca", status = if (id == "contradictory") Verdict.Status.Ok else Verdict.Status.WrongLanguage,
                    attempts = listOf(Attempt(2, raw = raw)),
                )) })
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
            override suspend fun detail(id: String): SessionDetailResult {
                val attempts = when (id) {
                    "missing" -> listOf(Attempt(2))
                    "unreadable" -> listOf(Attempt(2, raw = "not JSON"))
                    else -> listOf(Attempt(2), Attempt(3, raw = "{}"))
                }
                return SessionDetailResult.Found(detailOf(id).copy(session = detailOf(id).session.copy(
                    text = before, finalText = after, attempts = attempts), suggestions = listOf(if (id == "unreadable") row.copy(start = 6, end = 11) else row)))
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
    fun languageSwitchFailuresAndDismissalsDoNotReviveSavedMeaning() {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = SessionSettings("English", "moderate", "both", "gemini", "model", "prompt")
        val prompt = Prompt("", "{}")
        val raw = """{"status":"ok","language":"Catalan","has_errors":true,"corrected":"Com estàs?","meaning":"Old meaning"}"""
        val revision = (interpret("Com estas?", prompt.parseVerdict(raw)) as CheckResult.Reviewed).revision
        assertTrue(revision.edits.isNotEmpty())
        val details = mutableMapOf<String, SessionDetail>()
        for (edits in listOf(true, false)) {
            for (ending in listOf("failed", "dismissed", "returned-and-failed", "replacement", "ordinary-failure", "ordinary-unclear")) {
                val id = "$edits-$ending"
                var row = 0
                val recording = SessionRecording("Com estas?", SessionContext(
                    Opening(Origin.Menu, null, "ca", settings), "1", "device"), 1,
                    newId = { if (row++ == 0) id else "$id-$row" })
                recording.attempt(2, emptyList(), raw, null, null,
                    if (edits) revision else Revision("Com estas?", emptyList()), null, Verdict.Status.Ok)
                if (!ending.startsWith("ordinary")) recording.changeLanguage("es", 3)
                when (ending) {
                    "failed", "ordinary-failure", "returned-and-failed" ->
                        recording.attempt(4, emptyList(), null, "offline", null, null, null)
                    "replacement" -> recording.attempt(4, emptyList(), raw.replace("Catalan", "Spanish"),
                        null, null, revision, null, Verdict.Status.Ok)
                    "ordinary-unclear" -> recording.attempt(4, emptyList(), """{"status":"unclear"}""",
                        null, null, null, null, Verdict.Status.Unclear)
                }
                if (ending == "returned-and-failed") {
                    recording.changeLanguage("ca", 5)
                    recording.attempt(6, emptyList(), null, "offline", null, null, null)
                }
                details[id] = recording.close(7, null)
            }
        }
        val history = object : HistoryStore by SqliteHistoryStore(context, "unused") {
            override suspend fun detail(id: String) = SessionDetailResult.Found(details.getValue(id))
            override suspend fun save(session: SessionRecord) = error("Viewing must not save")
            override suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>) = error("Viewing must not close")
        }
        val model = SessionViewModel(history, { prompt }, AppLabels(context.packageManager), scope, {})
        try {
            details.forEach { (id, detail) ->
                model.load(id)
                waitFor { (model.page as? SessionPage.Shown)?.session?.id == id }
                val shown = model.page as SessionPage.Shown
                assertEquals(detail.session, shown.session)
                assertEquals(savedSuggestions(detail), shown.suggestions)
                if (id.endsWith("replacement") || id.contains("ordinary")) {
                    if (id == "false-ordinary-unclear") {
                        // Without saved suggestion rows the existing fallback selects the unclear answer.
                        assertSame(CheckResult.Unclear, shown.card?.result)
                    } else {
                        assertEquals(id, "Old meaning", (shown.card?.result as? CheckResult.Reviewed)?.meaning)
                    }
                } else {
                    assertTrue(id, shown.suggestions.isEmpty())
                    assertNull(id, shown.card)
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun readFailureCanBeRetriedAndActualAbsenceStaysMissing() {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reads = mutableListOf<String>()
        var fail = true
        val history = object : HistoryStore by SqliteHistoryStore(context, "unused") {
            override suspend fun detail(id: String): SessionDetailResult {
                reads += id
                return when {
                    id == "absent" -> SessionDetailResult.Missing
                    fail -> SessionDetailResult.Failed
                    else -> SessionDetailResult.Found(detailOf(id))
                }
            }
        }
        val model = SessionViewModel(history, { Prompt("", "{}") }, AppLabels(context.packageManager), scope, {})
        try {
            model.load("check")
            waitFor { model.page == SessionPage.Failed }
            fail = false
            model.retry()
            waitFor { model.page is SessionPage.Shown }
            assertEquals("check", (model.page as SessionPage.Shown).session.id)
            assertEquals(listOf("check", "check"), reads)

            // A failed refresh must replace the formerly visible saved text.
            fail = true
            model.load("check")
            waitFor { model.page == SessionPage.Failed }
            model.load("absent")
            waitFor { model.page == SessionPage.Missing }
            assertEquals(SessionDeletion.Ready, model.deletion)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun navigatingAwayCancelsAReadWithoutShowingFailureOnTheNextCheck() {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val pending = CompletableDeferred<SessionDetailResult>()
        val history = object : HistoryStore by SqliteHistoryStore(context, "unused") {
            override suspend fun detail(id: String): SessionDetailResult {
                if (id != "pending") return SessionDetailResult.Found(detailOf(id))
                started.complete(Unit)
                return try {
                    pending.await()
                } catch (expected: CancellationException) {
                    cancelled.complete(Unit)
                    throw expected
                }
            }
        }
        val model = SessionViewModel(history, { Prompt("", "{}") }, AppLabels(context.packageManager), scope, {})
        try {
            model.load("pending")
            waitFor { started.isCompleted }
            assertSame(SessionPage.Loading, model.page)
            model.load("next")
            waitFor { cancelled.isCompleted && model.page is SessionPage.Shown }
            assertEquals("next", (model.page as SessionPage.Shown).session.id)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun resumeRereadRemovesTextAndMeaningAfterAnotherHostDeletesOrClearsHistory() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        for (clear in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val history = SqliteHistoryStore(context, "resume-${if (clear) "clear" else "delete"}")
            val raw = """{"status":"ok","language":"Catalan","has_errors":false,"meaning":"Saved meaning"}"""
            val detail = detailOf("check").let { it.copy(session = it.session.copy(
                finalText = "Com estas?", attempts = listOf(Attempt(2, raw = raw)),
            )) }
            history.close(detail.session, detail.suggestions)
            val model = SessionViewModel(history, { Prompt("", "{}") }, AppLabels(context.packageManager), scope, {})
            try {
                model.load("check")
                waitFor { model.page is SessionPage.Shown }
                val shown = model.page as SessionPage.Shown
                assertEquals("Com estas?", shown.session.text)
                assertEquals("Com estas?", shown.session.finalText)
                assertEquals("Saved meaning", (shown.card?.result as? CheckResult.Reviewed)?.meaning)

                // Both Home hosts share this store. Only the resumed host rereads its detail.
                if (clear) history.clear() else assertTrue(history.delete("check"))
                assertSame(shown, model.page)
                model.load("check") // HomeActivity.onResume refreshes the current detail.
                waitFor { model.page == SessionPage.Missing }
                // Missing removes the Shown subtree, including selectable text and Meaning.
                assertEquals(SessionDeletion.Ready, model.deletion)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun resumeRereadCannotInterruptOrReviveAnAcknowledgedLocalDeletion() {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val write = CompletableDeferred<Boolean>()
        val started = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val history = object : HistoryStore by SqliteHistoryStore(context, "unused") {
            override suspend fun detail(id: String): SessionDetailResult {
                reads.incrementAndGet()
                return SessionDetailResult.Found(detailOf(id))
            }
            override suspend fun delete(id: String): Boolean {
                started.complete(Unit)
                return write.await()
            }
        }
        val model = SessionViewModel(history, { Prompt("", "{}") }, AppLabels(context.packageManager), scope, {})
        try {
            model.load("check")
            waitFor { model.page is SessionPage.Shown }
            val shown = model.page
            model.delete()
            waitFor { started.isCompleted }
            model.load("check")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, reads.get())
            assertEquals(SessionDeletion.Deleting, model.deletion)
            assertSame(shown, model.page)

            write.complete(true)
            waitFor { model.deletion == SessionDeletion.Deleted }
            model.load("check")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, reads.get())
            assertEquals(SessionDeletion.Deleted, model.deletion)
            assertSame(SessionPage.Missing, model.page)
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
            override suspend fun detail(id: String) = SessionDetailResult.Found(detailOf(id))
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
            override suspend fun detail(id: String) = SessionDetailResult.Found(detailOf(id))
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
                override suspend fun detail(id: String) = SessionDetailResult.Missing
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
