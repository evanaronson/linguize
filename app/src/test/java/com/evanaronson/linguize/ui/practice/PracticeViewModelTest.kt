package com.evanaronson.linguize.ui.practice

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.practice.AnswerResult
import com.evanaronson.linguize.core.practice.PracticeProgress
import com.evanaronson.linguize.core.practice.ReviewState
import com.evanaronson.linguize.core.practice.Schedule
import com.evanaronson.linguize.data.practice.PracticeStore
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.HistoryCursor
import com.evanaronson.linguize.history.HistoryPage
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.readEvidencePages
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionDetailResult
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SessionSummary
import com.evanaronson.linguize.history.SqliteHistoryStore
import com.evanaronson.linguize.history.SuggestionRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class PracticeViewModelTest {
    private val app = RuntimeEnvironment.getApplication() as App
    private val file = File(app.filesDir, "practice-test.json").also { it.delete() }
    private val store = PracticeStore(file)
    private val sessions = ConcurrentHashMap<String, SessionDetail>()
    private val revisions = MutableStateFlow(0L)
    private var now = 1_000_000_000_000L

    private val history = object : HistoryStore by SqliteHistoryStore(app, "unused") {
        override fun revisions() = revisions
        // This fixture owns page/detail rows; do not delegate its batch to the unused database.
        override fun evidenceRevision(): Long? = null
        override suspend fun evidence(limit: Int) = readEvidencePages(limit)

        override suspend fun page(before: HistoryCursor?, limit: Int): HistoryPage =
            HistoryPage(sessions.values.sortedByDescending { it.session.startedAt }.map {
                SessionSummary(it.session.id, it.session.startedAt, Origin.Menu, null, it.session.text, null, 1, 0, 1)
            }, null)

        override suspend fun detail(id: String): SessionDetailResult =
            sessions[id]?.let { SessionDetailResult.Found(it) } ?: SessionDetailResult.Missing
    }

    private val models = ViewModelStore()
    private val model by lazy {
        ViewModelProvider(models, viewModelFactory {
            initializer { PracticeViewModel(history, store, clock = { now }, zone = { ZoneOffset.UTC }, savedState = SavedStateHandle()) }
        })[PracticeViewModel::class.java]
    }

    @After fun close() {
        models.clear()
        file.delete()
    }

    private fun add(id: String, text: String, from: String, to: String, startedAt: Long) {
        val at = text.indexOf(from)
        sessions[id] = SessionDetail(
            SessionRecord(
                id = id, deviceId = "d", createdAt = startedAt, updatedAt = startedAt, startedAt = startedAt,
                closedAt = startedAt, origin = Origin.Menu, requestedLanguage = "es", text = text, textHash = "h",
                finalText = text.replaceRange(at, at + from.length, to), punctuation = "moderate", judgments = "both",
                provider = "gemini", model = "m", promptHash = "p", appVersion = "1",
            ),
            listOf(SuggestionRecord(
                id = "fix-$id", sessionId = id, deviceId = "d", createdAt = startedAt, updatedAt = startedAt, attempt = 0,
                kind = EditKind.Fix, start = at, end = at + from.length, fromText = from, toText = to,
                why = "Because", decision = Decision.Accepted, decidedAt = startedAt,
            )),
        )
    }

    @Test
    fun aRunRecordsAnswersAndDeletingTheCheckDropsItsItem() {
        add("one", "Nos vemos despues.", "despues", "después", 1)
        add("two", "Com estas?", "estas", "estàs", 2)
        waitFor { model.state.value.items?.size == 2 }
        assertEquals(2, model.state.value.due)

        model.start()
        val run = model.state.value.run!!
        assertEquals(listOf("fix-two", "fix-one"), run.queue.map { it.id })

        model.submit("estàs")
        assertEquals(AnswerResult.Correct, model.state.value.run!!.answered!!.result)
        waitFor { !model.state.value.saving }
        model.next()
        model.submit("despues")
        waitFor { !model.state.value.saving }
        model.next()
        // The missed item comes back at the end of the run.
        assertEquals("fix-one", model.state.value.run!!.current?.id)
        model.submit("después")
        model.next()
        assertTrue(model.state.value.run!!.finished)
        assertEquals(0, model.state.value.due)

        waitFor { readStates().keys == setOf("fix-one", "fix-two") }
        val saved = readStates()
        assertEquals(now + Schedule.DAY, saved.getValue("fix-two").due)
        // Only the first answer counts: the miss.
        assertEquals(now + Schedule.RETRY_AFTER, saved.getValue("fix-one").due)
        model.close()

        // Ten minutes later the miss is due again.
        now += Schedule.RETRY_AFTER
        model.tick()
        assertEquals(1, model.state.value.due)

        // Deleting the check in History removes its item and its saved state.
        sessions.remove("one")
        revisions.update { it + 1 }
        waitFor { model.state.value.items?.map { it.id } == listOf("fix-two") && readStates().keys == setOf("fix-two") }
        assertEquals(0, model.state.value.due)
    }

    @Test
    fun nothingDueOpensNoRun() {
        waitFor { model.state.value.items != null }
        model.start()
        assertEquals(null, model.state.value.run)
        assertEquals(0, model.state.value.due)
    }

    @Test
    fun blankAnswersAreIgnored() {
        add("one", "Nos vemos despues.", "despues", "después", 1)
        waitFor { model.state.value.items?.size == 1 }
        model.start()
        model.submit("   ")
        assertFalse(model.state.value.run!!.answered != null)
        model.reveal()
        assertTrue(model.state.value.run!!.answered!!.revealed)
        // Let the save finish: Robolectric drops main-thread work queued after a test,
        // which would leave the store's process-wide lock held for the next one.
        waitFor { readStates().keys == setOf("fix-one") }
    }

    /** Read from the file directly: blocking the main thread on the store's lock would deadlock its writer. */
    private fun readStates(): Map<String, ReviewState> =
        if (!file.exists()) emptyMap()
        else runCatching { Json.decodeFromString<PracticeProgress>(file.readText()).states }.getOrDefault(emptyMap())

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L // Generous: shared CI machines are slow.
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for practice state")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }
}
