package com.evanaronson.linguize.ui.time

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.FakeClient
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.QUESTION
import com.evanaronson.linguize.QUESTION_JSON
import com.evanaronson.linguize.QUESTION_VERDICT
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.time.ActiveTimer
import com.evanaronson.linguize.core.time.DraftTiming
import com.evanaronson.linguize.core.time.TimeLog
import com.evanaronson.linguize.core.time.TimeRecord
import com.evanaronson.linguize.fakeChecker
import com.evanaronson.linguize.history.CheckHistory
import com.evanaronson.linguize.history.HistoryCursor
import com.evanaronson.linguize.history.HistoryEnvironment
import com.evanaronson.linguize.history.HistoryPage
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.ReuseKey
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionDetailResult
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SessionSummary
import com.evanaronson.linguize.history.SuggestionRecord
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The card's review time on a real checker and History, with a fake model and clock: the chat
 * records writing and review, a popup records review only, and nothing History doesn't save is
 * timed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CardTimingTest {
    private var now = 1_000_000L
    private val recorded = CopyOnWriteArrayList<TimeRecord>()
    private val client = FakeClient { ModelAnswer(QUESTION_VERDICT, QUESTION_JSON) }
    private val checker = fakeChecker(FakePreferences(), client)
    @Volatile private var historyOn = true
    private val environment = object : HistoryEnvironment {
        override val enabled get() = historyOn
        override val deviceId = "device"
        override val appVersion = "1.0"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history = CheckHistory(NoStore, environment, scope)
    private val viewModels = ViewModelStore()
    private val check = ViewModelProvider(
        viewModels,
        viewModelFactory {
            initializer { CheckViewModel(checker = { checker }, history = history, timing = CardTiming(TimeLog { recorded += it }) { now }) }
        },
    )[CheckViewModel::class.java]

    @After fun stop() {
        viewModels.clear()
        scope.cancel()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }

    private fun reviewed(): CheckResult.Reviewed {
        waitFor("an answer") { check.state is CardState.Done }
        return (check.state as CardState.Done).result as CheckResult.Reviewed
    }

    @Test fun aPopupRecordsReviewOnlyFromTheResultToPutBack() {
        val answer = CompletableDeferred<Unit>()
        client.answer = { answer.await(); ModelAnswer(QUESTION_VERDICT, QUESTION_JSON) }
        check.check(QUESTION, Language.Catalan, Origin.Menu, "com.whatsapp")
        waitFor("the request") { client.checks.size == 1 }
        now += 10_000 // waiting for the model isn't review
        answer.complete(Unit)
        val fix = reviewed().revision.edits.single { it.kind == EditKind.Fix }
        now += 3_000
        check.accept(fix.id)
        now += 2_000
        check.dismiss(applied = true)

        val record = recorded.single()
        assertNull(record.writingMs)
        assertEquals(5_000L, record.reviewMs)
        assertEquals("menu", record.origin)
        assertEquals("ca", record.language)
        assertEquals(2, record.words)
        assertEquals(1, record.changesShown)
        assertEquals(1, record.changesAccepted)
        assertEquals(now, record.at)
        assertTrue(record.sessionId.isNotEmpty())
    }

    @Test fun theChatRecordsWritingAndEveryReviewOfTheMessage() {
        check.check(QUESTION, Language.Catalan, Origin.Chat)
        reviewed()
        now += 4_000
        val id = check.dismissSent(QUESTION, DraftTiming(writingMs = 9_000, earlierReviewMs = 2_000, messageId = "m1"))

        val record = recorded.single()
        assertEquals(id, record.sessionId)
        assertEquals(9_000L, record.writingMs)
        assertEquals(6_000L, record.reviewMs)
        assertEquals("chat", record.origin)
        assertEquals("m1", record.messageId)
        assertEquals(0, record.changesAccepted)
    }

    @Test fun reviewDoesntCountOffScreenAndALeftCardIsCapped() {
        check.check(QUESTION, Language.Catalan, Origin.Button)
        reviewed()
        now += 1_000
        check.timing.visible(false)
        now += 3_600_000
        check.timing.visible(true)
        now += 60 * 60_000
        check.dismiss(applied = true, waitMs = 1_000)
        // Accepting nothing puts back unchanged text: History doesn't save it, so nothing is timed.
        assertTrue(recorded.isEmpty())

        check.check(QUESTION, Language.Catalan, Origin.Button)
        val fix = reviewed().revision.edits.first()
        now += 1_000
        check.timing.visible(false)
        now += 3_600_000
        check.timing.visible(true)
        check.accept(fix.id)
        now += 60 * 60_000
        check.dismiss(applied = true)
        assertEquals(1_000L + ActiveTimer.REVIEW_IDLE_CAP_MS, recorded.single().reviewMs)
    }

    @Test fun nothingIsTimedWhenHistoryDoesntSave() {
        // Closed without putting anything back.
        check.check(QUESTION, Language.Catalan, Origin.Menu)
        check.accept(reviewed().revision.edits.first().id)
        now += 5_000
        check.dismiss(applied = false)
        // Try it is never saved.
        check.check(QUESTION, Language.Catalan, Origin.Tester)
        check.accept(reviewed().revision.edits.first().id)
        check.dismiss(applied = true)
        // History off.
        historyOn = false
        check.check(QUESTION, Language.Catalan, Origin.Chat)
        reviewed()
        check.dismissSent(QUESTION, DraftTiming(1_000))
        // Keep editing in the chat drops the card without saving.
        historyOn = true
        check.check(QUESTION, Language.Catalan, Origin.Chat)
        reviewed()
        now += 7_000
        assertEquals(7_000L, check.timing.reviewMs())
        check.discard()
        assertEquals(0L, check.timing.reviewMs())
        assertTrue(recorded.isEmpty())
    }

    private object NoStore : HistoryStore {
        override suspend fun save(session: SessionRecord) = Unit
        override suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>) = Unit
        override suspend fun reusable(key: ReuseKey, since: Long): SessionDetail? = null
        override suspend fun markAbandoned(now: Long) = Unit
        override fun count(): Flow<Int> = flowOf(0)
        override fun since(): Flow<Long?> = flowOf(null)
        override fun recent(limit: Int): Flow<List<SessionSummary>> = flowOf(emptyList())
        override fun revisions(): Flow<Long> = flowOf(0L)
        override suspend fun page(before: HistoryCursor?, limit: Int) = HistoryPage(emptyList(), null)
        override suspend fun detail(id: String) = SessionDetailResult.Missing
        override suspend fun delete(id: String) = true
        override suspend fun clear() = Unit
        override suspend fun export(out: OutputStream) = true
    }
}
