package com.evanaronson.linguize.ui.card

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.FakeClient
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.QUESTION
import com.evanaronson.linguize.QUESTION_JSON
import com.evanaronson.linguize.QUESTION_VERDICT
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.corpus.Span
import com.evanaronson.linguize.fakeChecker
import com.evanaronson.linguize.history.CheckHistory
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.HistoryEnvironment
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.SessionDetailResult
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SessionRecording
import com.evanaronson.linguize.history.SqliteHistoryStore
import com.evanaronson.linguize.history.corpus.CheckCorpusSource
import com.evanaronson.linguize.llm.ModelAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

/**
 * Lab, Leave as written: a check from another app that the writer closes with Leave as written
 * (clean, or every change skipped) is saved unchanged and visible in History, so everything
 * derived from History reads it. Closing any other way saves nothing, as before; Chat and Notes
 * are unchanged; with History off the button only closes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LeaveAsWrittenTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = SqliteHistoryStore(context, NAME)
    private var enabled = true
    private val environment = object : HistoryEnvironment {
        override val enabled get() = this@LeaveAsWrittenTest.enabled
        override val deviceId = "device"
        override val appVersion = "1.0"
    }
    private val clean = "Com estàs?"
    private val cleanVerdict = Verdict(Verdict.Status.Ok, language = "Catalan")
    private val client = FakeClient { request ->
        if (request.text == clean) ModelAnswer(cleanVerdict, """{"status":"ok","has_errors":false}""")
        else ModelAnswer(QUESTION_VERDICT, QUESTION_JSON)
    }
    private val checker = fakeChecker(FakePreferences(), client)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history = CheckHistory(store, environment, scope)
    private val viewModels = ViewModelStore()
    private val check = ViewModelProvider(
        viewModels,
        viewModelFactory { initializer { CheckViewModel(checker = { checker }, history = history) } },
    )[CheckViewModel::class.java]

    @After fun tearDown() {
        viewModels.clear()
        scope.cancel()
        context.deleteDatabase(NAME)
    }

    private fun done(): CardState.Done {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (check.state !is CardState.Done) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for an answer")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        return check.state as CardState.Done
    }

    private fun visible(): List<String> = runBlocking {
        history.flush()
        store.recent(10).first().map { it.id }
    }

    @Test fun aCleanCheckLeftAsWrittenIsSavedUnchangedAndVisible() = runBlocking<Unit> {
        check.check(clean, null, Origin.Menu, "com.whatsapp")
        assertTrue((done().result as CheckResult.Reviewed).looksGood)
        val id = check.leaveAsWritten()!!
        assertNull("The card is closed", check.state)
        assertEquals(listOf(id), visible())
        assertEquals(1, store.count().first())
        val detail = (store.detail(id) as SessionDetailResult.Found).detail
        assertEquals(Outcome.AsWritten, detail.session.outcome)
        assertEquals(clean, detail.session.finalText)
        assertEquals(Origin.Menu, detail.session.origin)
        assertTrue("Nothing to decide on a clean check", detail.suggestions.isEmpty())
    }

    @Test fun theDerivedCorpusReadsItAsTheWritersOwnCorrectWords() = runBlocking<Unit> {
        check.check(clean, null, Origin.Button, "com.whatsapp")
        done()
        check.leaveAsWritten()
        visible()
        val utterances = CheckCorpusSource(store).utterances()
        assertEquals(listOf(clean), utterances.map { it.text })
        assertTrue("Nothing marked wrong", utterances.single().corrected.isEmpty())

        // Checking the same words again later is the writer's writing again, not text Linguize put back.
        check.check(clean, null, Origin.Menu)
        done()
        check.leaveAsWritten()
        visible()
        assertEquals(2, CheckCorpusSource(store).utterances().size)
    }

    @Test fun closingWithoutTheButtonSavesNothingVisible() = runBlocking<Unit> {
        check.check(clean, null, Origin.Menu)
        done()
        check.dismiss(applied = false)
        assertTrue(visible().isEmpty())
        // Even a host that says it applied: nothing changed, so nothing is put back or saved.
        check.check(clean, null, Origin.Menu)
        done()
        check.dismiss(applied = true)
        assertTrue(visible().isEmpty())
        assertEquals(0, store.count().first())
    }

    @Test fun withHistoryOffTheButtonOnlyCloses() = runBlocking<Unit> {
        enabled = false
        check.check(clean, null, Origin.Menu)
        done()
        assertNull(check.leaveAsWritten())
        assertNull(check.state)
        assertTrue(visible().isEmpty())
    }

    @Test fun everyChangeSkippedIsSavedAsWrittenWithTheChangesNotAccepted() = runBlocking<Unit> {
        check.check(QUESTION, null, Origin.Menu)
        assertEquals(1, (done().result as CheckResult.Reviewed).revision.edits.size)
        val id = check.leaveAsWritten()!!
        assertEquals(listOf(id), visible())
        val detail = (store.detail(id) as SessionDetailResult.Found).detail
        assertEquals(Outcome.AsWritten, detail.session.outcome)
        assertEquals(QUESTION, detail.session.finalText)
        assertEquals(listOf(Decision.Ignored), detail.suggestions.map { it.decision })
        // The corpus still reads the check's judgment: the word the fix changes is marked wrong.
        assertEquals(listOf(Span(4, 9)), CheckCorpusSource(store).utterances().single().corrected)
    }

    @Test fun withAnythingAcceptedItClosesWithoutSaving() = runBlocking<Unit> {
        check.check(QUESTION, null, Origin.Menu)
        done()
        check.acceptAll(EditKind.Fix)
        assertNull(check.leaveAsWritten())
        assertTrue(visible().isEmpty())
    }

    @Test fun theAppsOwnCardsAreStillNeverSaved() = runBlocking<Unit> {
        // Notes and Try it check as Origin.Tester: never recorded, Leave as written or not.
        check.check(clean, null, Origin.Tester)
        done()
        assertNull(check.leaveAsWritten())
        assertTrue(visible().isEmpty())
    }

    @Test fun chatStillSavesSentAsWrittenAsApplied() = runBlocking<Unit> {
        check.check(clean, null, Origin.Chat)
        done()
        val id = check.dismissSent(clean)!!
        assertEquals(listOf(id), visible())
        assertEquals(Outcome.Applied, (store.detail(id) as SessionDetailResult.Found).detail.session.outcome)
    }

    @Test fun olderUnchangedRecordsKeepTheirVisibility() = runBlocking<Unit> {
        // A menu check closed as applied with its text unchanged (no build saves one, an older one
        // might have) stays hidden: only the new outcome makes an unchanged other-app check visible.
        val now = 1_000L
        val session = SessionRecord(
            id = "older", deviceId = "device", createdAt = now, updatedAt = now, startedAt = now, closedAt = now,
            origin = Origin.Menu, text = clean, textHash = SessionRecording.hash(clean), finalText = clean,
            outcome = Outcome.Applied, punctuation = "moderate", judgments = "both", provider = "gemini",
            model = "m", promptHash = "p", appVersion = "1.0",
        )
        store.close(session, emptyList())
        store.close(session.copy(id = "none", outcome = Outcome.None, finalText = null), emptyList())
        assertTrue(store.recent(10).first().isEmpty())
        assertEquals(SessionDetailResult.Missing, store.detail("older"))
        // A left-as-written record whose final text isn't its text is not one: hidden too.
        store.close(session.copy(id = "odd", outcome = Outcome.AsWritten, finalText = "Other"), emptyList())
        assertTrue(store.recent(10).first().isEmpty())
    }

    companion object {
        private const val NAME = "leave-as-written-test"
    }
}
