package com.evanaronson.linguize.ui.card

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
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.fakeChecker
import com.evanaronson.linguize.history.CheckHistory
import com.evanaronson.linguize.history.HistoryEnvironment
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Opening
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.ReuseKey
import com.evanaronson.linguize.history.SessionContext
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SessionRecording
import com.evanaronson.linguize.history.SessionSummary
import com.evanaronson.linguize.history.SuggestionRecord
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.ModelAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The card's view model on a real checker and history, with a fake provider and store:
 * what survives a re-check, what a reused verdict brings, and what each way of closing
 * records. Robolectric runs the main thread; history writes run on a background scope.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CheckViewModelTest {
    private val preferences = FakePreferences()
    private val client = FakeClient { ModelAnswer(QUESTION_VERDICT, QUESTION_JSON) }
    private val checker = fakeChecker(preferences, client)
    private val store = RecordingStore()
    private val environment = object : HistoryEnvironment {
        override val enabled = true
        override val deviceId = "device"
        override val appVersion = "1.0"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history = CheckHistory(store, environment, scope)
    private val viewModels = ViewModelStore()
    private val check = ViewModelProvider(
        viewModels,
        viewModelFactory { initializer { CheckViewModel(checker = { checker }, history = history) } },
    )[CheckViewModel::class.java]

    @After
    fun stop() {
        viewModels.clear()
        scope.cancel()
    }

    /** Runs the main thread until [condition] holds. */
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }

    /** Runs the main thread for a moment, for things that should not happen. */
    private fun settle() = repeat(25) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(4)
    }

    private fun done(): CardState.Done {
        waitFor("an answer") { check.state is CardState.Done }
        return check.state as CardState.Done
    }

    private fun reviewed() = done().result as CheckResult.Reviewed

    /** Waits for history's writes, then gives the sessions closed so far. */
    private fun closed(): List<SessionRecord> {
        history.awaitWrites(5_000)
        return store.closed.toList()
    }

    @Test
    fun acceptedChangesSurviveARecheckAndAnAnswer() {
        check.check(QUESTION, Language.Catalan, Origin.Tester)
        assertEquals(1, reviewed().revision.edits.count { it.kind == EditKind.Fix })
        check.acceptAll(EditKind.Fix)
        assertEquals("Com estàs?", check.workingText)

        check.recheck()
        // While the new answer comes, what was accepted still counts.
        assertTrue(check.state is CardState.Loading)
        assertEquals("Com estàs?", check.workingText)
        assertEquals(1, reviewed().revision.acceptedCount)
        assertEquals("Com estàs?", check.workingText)

        check.settle("tu", "informal")
        assertEquals(1, reviewed().revision.acceptedCount)
        assertEquals(listOf(Settled("tu", "informal")), client.checks.last().third.settled)
        assertEquals(listOf(Settled("tu", "informal")), done().settled)
        assertEquals(Language.Catalan, client.checks.last().third.language)
        assertEquals(3, client.checks.size)
    }

    @Test
    fun aFreshCheckStartsOver() {
        check.check(QUESTION, null, Origin.Menu)
        reviewed()
        check.acceptAll(EditKind.Fix)
        check.check(QUESTION, null, Origin.Menu)
        assertNull(check.workingText)
        assertEquals(0, reviewed().revision.acceptedCount)
        // The first card's change never went back to the app.
        val first = closed().single()
        assertNull(first.finalText)
        assertEquals(Outcome.None, first.outcome)
    }

    @Test
    fun aReusedVerdictBringsTheAnswersItWasMadeWith() {
        val settled = listOf(Settled("tu", "informal"))
        store.kept = keptSession(settled)
        // The model never answers, so the kept verdict wins the race.
        client.answer = { awaitCancellation() }
        check.check(QUESTION, null, Origin.Menu, hostApp = "org.telegram.messenger")
        assertEquals(settled, done().settled)
        assertTrue(done().result is CheckResult.Reviewed)

        // A re-check sends them to the model.
        client.answer = { ModelAnswer(QUESTION_VERDICT, QUESTION_JSON) }
        check.recheck()
        waitFor("the model asked again") { client.checks.size == 2 }
        assertEquals(settled, client.checks.last().third.settled)
        done()
        check.dismiss(applied = false)
        val session = closed().single()
        assertEquals("earlier", session.attempts.first().reusedFrom)
        assertEquals(settled, session.attempts.first().settled)
        assertNull(session.attempts.last().reusedFrom)
    }

    @Test
    fun dismissingWithTheTextHandedBackRecordsItAsApplied() {
        check.check(QUESTION, Language.Catalan, Origin.Menu)
        reviewed()
        check.acceptAll(EditKind.Fix)
        check.dismiss(applied = true)
        assertNull(check.state)
        assertNull(check.workingText)
        val session = closed().single()
        assertEquals("Com estàs?", session.finalText)
        assertEquals(Outcome.Applied, session.outcome)
        assertEquals("ca", session.requestedLanguage)
        assertEquals("gemini", session.provider)
    }

    @Test
    fun dismissingWithoutHandingItBackAppliesNothing() {
        check.check(QUESTION, null, Origin.Menu)
        reviewed()
        check.acceptAll(EditKind.Fix)
        check.dismiss(applied = false)
        val session = closed().single()
        assertNull(session.finalText)
        assertEquals(Outcome.None, session.outcome)
    }

    @Test
    fun aCardGoneWithoutDismissAppliedNothing() {
        check.check(QUESTION, null, Origin.Button)
        reviewed()
        check.acceptAll(EditKind.Fix)
        viewModels.clear()
        val session = closed().single()
        assertNull(session.finalText)
        assertEquals(Outcome.None, session.outcome)
    }

    @Test
    fun aFailureIsShownAndKeptByItsToken() {
        client.answer = { throw CheckFailure(CheckFailure.Reason.RateLimited, "slow down") }
        check.check(QUESTION, null, Origin.Button)
        waitFor("the failure") { check.state is CardState.Failed }
        assertEquals(CardState.Failed(CheckFailure.Reason.RateLimited, "slow down"), check.state)
        check.dismiss(applied = false)
        val session = closed().single()
        assertEquals("rate_limited", session.attempts.single().failure)
        assertEquals(Outcome.Failed, session.outcome)
    }

    @Test
    fun theTryItCardIsNeverRecorded() {
        store.kept = keptSession(emptyList())
        check.check(QUESTION, Language.Catalan, Origin.Tester)
        reviewed()
        check.acceptAll(EditKind.Fix)
        check.changeLanguage(Language.Spanish)
        reviewed()
        check.recheck()
        reviewed()
        check.dismiss(applied = true)
        check.check(QUESTION, null, Origin.Tester)
        reviewed()
        viewModels.clear()
        history.awaitWrites(5_000)
        // Every check went to the model; nothing was looked up, saved or closed.
        assertEquals(4, client.checks.size)
        assertEquals(0, store.lookups.get())
        assertEquals(0, store.saves.get())
        assertTrue(store.closed.isEmpty())
    }

    @Test
    fun anotherLanguageOnTheSameCardIsTheSameSessionWithTheLastLanguage() {
        check.check(QUESTION, Language.Catalan, Origin.Button, hostApp = "org.telegram.messenger")
        reviewed()
        check.settle("tu", "informal")
        reviewed()
        check.acceptAll(EditKind.Fix)
        assertEquals("Com estàs?", check.workingText)

        check.changeLanguage(Language.Spanish)
        // A review in another language: nothing accepted or answered is carried over.
        assertNull(check.workingText)
        assertEquals(0, reviewed().revision.acceptedCount)
        assertEquals(emptyList<Settled>(), done().settled)
        assertEquals(Language.Spanish, client.checks.last().third.language)
        assertEquals(emptyList<Settled>(), client.checks.last().third.settled)

        check.changeLanguage(null)
        reviewed()
        assertEquals(null, client.checks.last().third.language)
        check.changeLanguage(Language.Catalan)
        reviewed()
        check.acceptAll(EditKind.Fix)
        assertEquals(5, client.checks.size)
        // Nothing is closed while the card is up.
        assertTrue(closed().isEmpty())

        check.dismiss(applied = true)
        val session = closed().single()
        assertEquals("ca", session.requestedLanguage)
        assertEquals(listOf("ca", "ca", "es", null, "ca"), session.attempts.map { it.requestedLanguage })
        assertEquals("Com estàs?", session.finalText)
        assertEquals(Outcome.Applied, session.outcome)
        assertEquals("org.telegram.messenger", session.hostApp)
    }

    @Test
    fun anotherLanguageWithNoCardDoesNothing() {
        check.changeLanguage(Language.Spanish)
        settle()
        assertTrue(client.checks.isEmpty())
        assertNull(check.state)
    }

    @Test
    fun backFromSettingsChecksAgainOnlyWhenSomethingChanged() {
        check.check(QUESTION, null, Origin.Button)
        reviewed()
        check.acceptAll(EditKind.Fix)
        check.recheckIfStale()
        settle()
        assertEquals(1, client.checks.size)

        preferences.punctuation = Punctuation.Strict
        check.recheckIfStale()
        waitFor("a re-check") { client.checks.size == 2 }
        assertEquals(Punctuation.Strict, client.checks.last().third.punctuation)
        assertEquals(1, reviewed().revision.acceptedCount)
        // The re-check ran with other settings, still in the card's one session.
        assertTrue(closed().isEmpty())
        check.dismiss(applied = true)
        val session = closed().single()
        assertEquals("strict", session.punctuation)
        assertEquals(listOf("moderate", "strict"), session.attempts.map { it.punctuation })
        assertEquals(Outcome.Applied, session.outcome)
    }

    @Test
    fun backFromSettingsRetriesAFailure() {
        client.answer = { throw CheckFailure(CheckFailure.Reason.NoKey) }
        check.check(QUESTION, null, Origin.Button)
        waitFor("the failure") { check.state is CardState.Failed }
        client.answer = { ModelAnswer(QUESTION_VERDICT, QUESTION_JSON) }
        check.recheckIfStale()
        reviewed()
        assertEquals(2, client.checks.size)
    }

    @Test
    fun backFromSettingsWithNoCardDoesNothing() {
        check.recheckIfStale()
        settle()
        assertTrue(client.checks.isEmpty())
        assertNull(check.state)
    }

    /** A session on [QUESTION] closed earlier, whose one attempt the model answered with [QUESTION_JSON]. */
    private fun keptSession(settled: List<Settled>): SessionDetail {
        // Made with the settings the check runs with: a kept answer is only shown for those.
        val settings = runBlocking { checker.context().stored }
        val context = SessionContext(Opening(Origin.Menu, null, null, settings), appVersion = "1.0", deviceId = "device")
        val recording = SessionRecording(QUESTION, context, 500, newId = { "earlier" })
        recording.attempt(600, settled, QUESTION_JSON, null, null, null, null)
        return recording.close(700, null)
    }

    /** Remembers the sessions it was asked to close, and counts saves and lookups; [kept] is what every reuse lookup finds. */
    private class RecordingStore : HistoryStore {
        val closed: MutableList<SessionRecord> = Collections.synchronizedList(mutableListOf())
        val saves = AtomicInteger()
        val lookups = AtomicInteger()

        @Volatile var kept: SessionDetail? = null

        override suspend fun save(session: SessionRecord) {
            saves.incrementAndGet()
        }
        override suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>) {
            closed += session
        }
        override suspend fun reusable(key: ReuseKey, since: Long): SessionDetail? {
            lookups.incrementAndGet()
            return kept
        }
        override suspend fun markAbandoned(now: Long) = Unit
        override fun count(): Flow<Int> = flowOf(0)
        override fun since(): Flow<Long?> = flowOf(null)
        override fun recent(limit: Int): Flow<List<SessionSummary>> = flowOf(emptyList())
        override suspend fun detail(id: String): SessionDetail? = null
        override suspend fun delete(id: String) = Unit
        override suspend fun clear() = Unit
        override suspend fun export(out: OutputStream) = true
    }
}
