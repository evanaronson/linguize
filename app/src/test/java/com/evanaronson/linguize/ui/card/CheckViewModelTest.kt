package com.evanaronson.linguize.ui.card

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.accessibility.OverlayWindow
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.FakeClient
import com.evanaronson.linguize.App
import com.evanaronson.linguize.menu.CheckActivity
import com.evanaronson.linguize.ui.home.HomeActivity
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.QUESTION
import com.evanaronson.linguize.QUESTION_JSON
import com.evanaronson.linguize.QUESTION_VERDICT
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Assumption
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.VerdictChange
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.data.CheckPreferences
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
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The card's view model on a real checker and history, with a fake provider and store:
 * what survives a re-check, what a reused verdict brings, and what each way of closing
 * records. Robolectric runs the main thread; history writes run on a background scope.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CheckViewModelTest {
    @Test fun clarifyingALinkedSuggestionRechecksTheWholeOriginalAndMayRemoveIt() {
        val original = "I saw her duck."
        client.answer = { request ->
            val verdict = if (request.settled.isEmpty()) {
                Verdict(Verdict.Status.Ok, language = "English", assumptions = listOf(
                    Assumption("Meaning of duck", "She lowered her head", alternatives = listOf("Her bird"))),
                    moreNatural = true, natural = "I saw her lower her head.", naturalChanges = listOf(
                        VerdictChange("her duck", "her lower her head", confidence = 1, impact = 3, assumptionIndices = listOf(0))))
            } else Verdict(Verdict.Status.Ok, language = "English")
            ModelAnswer(verdict, Json.encodeToString(verdict))
        }
        check.check(original, null, Origin.Menu)
        assertEquals(listOf(0), reviewed().revision.edits.single().assumptionIndices)
        check.settle("Meaning of duck", "Her bird")
        assertTrue(reviewed().revision.edits.isEmpty())
        assertTrue(reviewed().assumptions.isEmpty())
        assertEquals(2, client.checks.size)
        assertEquals(original, client.checks.last().third.text)
        assertEquals(listOf(Settled("Meaning of duck", "Her bird")), client.checks.last().third.settled)
    }
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
    fun switchingToUnderstandDiscardsTheProvisionalCheckWithoutApplying() {
        check.check(QUESTION, null, Origin.Button)
        reviewed()
        assertFalse(check.suspendForUnderstand())
        runBlocking { history.flush() }
        assertNull(check.state)
        assertNull(check.workingText)
        assertTrue(closed().isEmpty())
        assertEquals(1, store.deleted.size)
        // Closing the Understand popup or clearing the host cannot recreate that evidence.
        check.dismiss(applied = false)
        viewModels.clear()
        runBlocking { history.flush() }
        assertTrue(closed().isEmpty())
        assertEquals(1, store.deleted.size)
    }

    @Test
    fun switchingDuringLoadingCancelsAndDiscardsTheCheck() {
        client.answer = { awaitCancellation() }
        check.check(QUESTION, null, Origin.Button)
        waitFor("a provisional record") { store.saves.get() > 0 }
        assertFalse(check.suspendForUnderstand())
        runBlocking { history.flush() }
        settle()
        assertNull(check.state)
        assertTrue(closed().isEmpty())
        assertEquals(1, store.deleted.size)
    }

    @Test
    fun switchingToUnderstandKeepsAcceptedWorkAndClosingDoesNotApplyIt() {
        check.check(QUESTION, null, Origin.Button)
        reviewed()
        check.acceptAll(EditKind.Fix)
        val retained = check.state
        assertTrue(check.suspendForUnderstand())
        assertEquals(retained, check.state)
        assertEquals("Com estàs?", check.workingText)
        assertTrue(store.deleted.isEmpty())
        // Even if clipboard capture fails, returning uses this same state and Undo stack.
        check.undo()
        assertNull(check.workingText)
        assertTrue(check.suspendForUnderstand())
        check.dismiss(applied = false)
        assertNull(closed().single().finalText)
        assertTrue(store.deleted.isEmpty())
    }

    @Test
    fun copiedAndClarifiedChecksAreRetainedWithoutAcceptedEdits() {
        check.check(QUESTION, null, Origin.Button)
        reviewed()
        check.copied(EditKind.Fix)
        assertTrue(check.suspendForUnderstand())
        assertNull(check.workingText)
        check.dismiss(applied = false)
        assertEquals(Outcome.Copied, closed().single().outcome)

        check.check(QUESTION, null, Origin.Button)
        reviewed()
        check.settle("tu", "informal")
        val answered = done()
        assertTrue(check.suspendForUnderstand())
        assertEquals(answered, check.state)
        assertEquals(listOf(Settled("tu", "informal")), answered.settled)
        assertTrue(store.deleted.isEmpty())
    }

    @Test
    fun acceptAllKeepsTheFinalTextUntilApplyWithoutTouchingTheClipboard() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("existing", "leave this alone"))
        check.check(QUESTION, null, Origin.Button)
        reviewed()
        var putBack: String? = null
        val actions = CardActions.of(
            check, context,
            onClose = {
                putBack = check.workingText
                check.dismiss(applied = true)
            },
            onOpenSettings = null,
        )
        actions.review!!.onAcceptAll(EditKind.Fix)
        assertNull(putBack)
        assertTrue(reviewed().isResolved)
        assertEquals("Com estàs?", reviewed().revision.preview(EditKind.Fix).text)
        assertTrue(closed().isEmpty())
        actions.review!!.onApply()
        assertEquals("Com estàs?", putBack)
        assertEquals("leave this alone", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(Outcome.Applied, closed().single().outcome)
    }

    @Test
    fun copyAfterIndividualAcceptanceKeepsTheCardOpenUntilApply() = assertCompletedReviewCanBeCopied(acceptAll = false)

    @Test
    fun copyAfterAcceptAllKeepsTheCardOpenUntilApply() = assertCompletedReviewCanBeCopied(acceptAll = true)

    private fun assertCompletedReviewCanBeCopied(acceptAll: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        check.check(QUESTION, null, Origin.Button)
        val fix = reviewed().revision.edits.single { it.kind == EditKind.Fix }
        var replacements = 0
        val actions = CardActions.of(
            check, context,
            onClose = {
                replacements++
                check.dismiss(applied = true)
            },
            onOpenSettings = null,
        )
        if (acceptAll) actions.review!!.onAcceptAll(EditKind.Fix) else actions.review!!.onAccept(fix.id)
        assertTrue(reviewed().isResolved)
        val preview = reviewed().revision.preview(EditKind.Fix).text
        assertEquals("Com estàs?", preview)
        assertEquals(0, replacements)
        assertTrue(closed().isEmpty())
        // The same Undo remains available after either route to a completed review.
        actions.review!!.onUndo()
        assertFalse(reviewed().isResolved)
        assertNull(check.workingText)
        if (acceptAll) actions.review!!.onAcceptAll(EditKind.Fix) else actions.review!!.onAccept(fix.id)
        actions.onCopy(reviewed().revision.preview(EditKind.Fix).text, EditKind.Fix)
        assertEquals(0, replacements)
        assertEquals(preview, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(reviewed().isResolved)
        assertEquals(preview, check.workingText)
        assertTrue(closed().isEmpty())
        // Copy must leave review actions alive, including Undo and a later Apply.
        actions.review!!.onUndo()
        assertFalse(reviewed().isResolved)
        actions.review!!.onAcceptAll(EditKind.Fix)
        actions.review!!.onApply()
        assertEquals(1, replacements)
        val session = closed().single()
        assertEquals(preview, session.finalText)
        assertEquals(Outcome.Applied, session.outcome)
    }

    @Test
    fun overlayReportsReplacementBeforeClearingTheCheck() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        check.check(QUESTION, null, Origin.Button)
        reviewed()
        check.acceptAll(EditKind.Fix)
        val window = OverlayWindow(context, onBack = {})
        window.viewModelStore.put("check", check)
        window.remove {
            // Clearing first would lose this text and close History as not applied.
            assertEquals("Com estàs?", check.workingText)
            check.dismiss(applied = true)
        }
        assertEquals(Outcome.Applied, closed().single().outcome)
    }

    @Test
    fun failedReplacementDoesNotBecomeACopyOrOverwriteTheClipboard() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("existing", "leave this alone"))
        check.check(QUESTION, null, Origin.Button)
        reviewed()
        val actions = CardActions.of(
            check, context,
            onClose = { check.dismiss(applied = false) },
            onOpenSettings = null,
        )
        actions.review!!.onAcceptAll(EditKind.Fix)
        assertTrue(closed().isEmpty())
        actions.review!!.onApply()
        assertEquals("leave this alone", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(Outcome.None, closed().single().outcome)
    }

    @Test
    fun acceptedChangesSurviveARecheckAndAnAnswer() {
        check.check(QUESTION, Language.Catalan, Origin.Tester)
        assertEquals(1, reviewed().revision.edits.count { it.kind == EditKind.Fix })
        check.acceptAll(EditKind.Fix)
        assertEquals("Com estàs?", check.workingText)

        // Hold the fake answer so these assertions cannot race an immediate response.
        val answer = CompletableDeferred<Unit>()
        client.answer = {
            answer.await()
            ModelAnswer(QUESTION_VERDICT, QUESTION_JSON)
        }
        try {
            check.recheck()
            waitFor("recheck request") { client.checks.size == 2 }
            // While the new answer comes, what was accepted still counts.
            assertTrue(check.state is CardState.Loading)
            assertEquals("Com estàs?", check.workingText)
        } finally {
            answer.complete(Unit)
        }
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
    fun reusedUnusableSuggestionsRemainIncomplete() {
        store.kept = keptSession(emptyList(), """{"status":"ok","language":"Catalan","more_natural":true}""")
        client.answer = { awaitCancellation() }
        check.check(QUESTION, null, Origin.Menu)

        assertEquals(setOf(EditKind.Natural), reviewed().withheld)
        assertFalse(reviewed().looksGood)
        assertFalse(reviewed().isResolved)
        assertTrue(reviewed().revision.edits.isEmpty())
    }

    @Test
    fun acceptingAndUndoingSafeFixesKeepsTheIncompleteJudgment() {
        client.answer = {
            ModelAnswer(QUESTION_VERDICT.copy(moreNatural = true, natural = ""),
                """{"status":"ok","has_errors":true,"corrected":"Com estàs?","more_natural":true}""")
        }
        check.check(QUESTION, null, Origin.Button)
        assertEquals(setOf(EditKind.Natural), reviewed().withheld)

        assertFalse(check.acceptAll(EditKind.Fix))
        assertEquals("Com estàs?", check.workingText)
        assertEquals(setOf(EditKind.Natural), reviewed().withheld)
        check.undo()
        assertNull(check.workingText)
        assertEquals(setOf(EditKind.Natural), reviewed().withheld)
        assertFalse(reviewed().looksGood)
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
    @Config(application = App::class)
    fun openingTheAppDoesNotFinishTheSelectionOrLoseAcceptedEdits() {
        check.check(QUESTION, null, Origin.Menu)
        reviewed()
        check.acceptAll(EditKind.Fix)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, CheckActivity::class.java)
            .setAction(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, QUESTION)
        val controller = Robolectric.buildActivity(CheckActivity::class.java, intent)
        val activity = controller.get()
        activity.viewModelStore.put(
            "androidx.lifecycle.ViewModelProvider.DefaultKey:" + CheckViewModel::class.java.canonicalName,
            check,
        )
        controller.create().start().resume()
        activity.openApp()
        val opened = shadowOf(activity).nextStartedActivity
        assertEquals(HomeActivity::class.java.name, opened.component?.className)
        assertTrue(opened.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertFalse(activity.isFinishing)
        assertEquals("Com estàs?", check.workingText)
        assertTrue(closed().isEmpty())
        controller.pause().resume()
        settle()
        assertEquals(1, client.checks.size)
        assertEquals("Com estàs?", check.workingText)
        activity.finish()
        assertEquals("Com estàs?", shadowOf(activity).resultIntent.getStringExtra(Intent.EXTRA_PROCESS_TEXT))
        assertEquals(Outcome.Applied, closed().single().outcome)
        controller.pause().stop().destroy()
    }

    @Test
    fun backFromSettingsWithNoCardDoesNothing() {
        check.recheckIfStale()
        settle()
        assertTrue(client.checks.isEmpty())
        assertNull(check.state)
    }

    @Test
    @Config(application = App::class)
    fun aReadOnlySelectionNeverReturnsAReplacementEvenWithAcceptedState() {
        check.check(QUESTION, null, Origin.Menu)
        reviewed()
        check.acceptAll(EditKind.Fix)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, CheckActivity::class.java)
            .setAction(Intent.ACTION_PROCESS_TEXT)
            .putExtra(Intent.EXTRA_PROCESS_TEXT, QUESTION)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        val controller = Robolectric.buildActivity(CheckActivity::class.java, intent)
        val activity = controller.get()
        activity.viewModelStore.put(
            "androidx.lifecycle.ViewModelProvider.DefaultKey:" + CheckViewModel::class.java.canonicalName,
            check,
        )
        controller.create().start().resume()
        activity.finish()
        assertEquals(android.app.Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertNull(closed().single().finalText)
        controller.pause().stop().destroy()
    }

    @Test
    @Config(application = App::class)
    fun anUnrelatedIntentNeverStartsACheck() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, CheckActivity::class.java)
            .setAction(Intent.ACTION_SEND).putExtra(Intent.EXTRA_PROCESS_TEXT, QUESTION)
        val controller = Robolectric.buildActivity(CheckActivity::class.java, intent)
        val activity = controller.get()
        activity.viewModelStore.put(
            "androidx.lifecycle.ViewModelProvider.DefaultKey:" + CheckViewModel::class.java.canonicalName,
            check,
        )
        controller.create()
        settle()
        assertTrue(activity.isFinishing)
        assertNull(check.state)
        assertTrue(client.checks.isEmpty())
        controller.destroy()
    }

    @Test
    fun lateRetryAndAssumptionCallbacksCannotReopenADismissedCard() {
        check.check(QUESTION, null, Origin.Menu)
        reviewed()
        check.dismiss(applied = false)
        check.recheck()
        check.settle("tu", "informal")
        settle()
        assertNull(check.state)
        assertEquals(1, client.checks.size)
        assertEquals(1, closed().size)
    }

    @Test
    fun aSettingsReadFromAnOlderCardCannotRestartANewerCheck() {
        val pauseRead = AtomicBoolean(false)
        val reading = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val preferences = object : CheckPreferences by this.preferences {
            override val punctuation: Punctuation
                get() {
                    val value = this@CheckViewModelTest.preferences.punctuation
                    if (pauseRead.compareAndSet(true, false)) {
                        reading.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                    }
                    return value
                }
        }
        val checker = Checker(preferences, { "key" }, Prompt("instructions", "{}"), Provider.entries.associateWith { client })
        val card = CheckViewModel({ checker }, history)
        viewModels.put("settings-race", card)
        try {
            card.check(QUESTION, null, Origin.Menu)
            waitFor("first answer") { card.state is CardState.Done }
            pauseRead.set(true)
            card.recheckIfStale()
            waitFor("settings read") { reading.count == 0L }
            this.preferences.punctuation = Punctuation.Strict
            card.check(QUESTION, null, Origin.Menu)
            waitFor("new answer") { card.state is CardState.Done && client.checks.size == 2 }
            resume.countDown()
            settle()
            assertEquals(2, client.checks.size)
            assertTrue(card.state is CardState.Done)
        } finally {
            resume.countDown()
        }
    }

    /** A session on [QUESTION] closed earlier, whose one attempt the model answered with [QUESTION_JSON]. */
    private fun keptSession(settled: List<Settled>, raw: String = QUESTION_JSON): SessionDetail {
        // Made with the settings the check runs with: a kept answer is only shown for those.
        val settings = runBlocking { checker.context().stored }
        val context = SessionContext(Opening(Origin.Menu, null, null, settings), appVersion = "1.0", deviceId = "device")
        val recording = SessionRecording(QUESTION, context, 500, newId = { "earlier" })
        recording.attempt(600, settled, raw, null, null, null, null)
        return recording.close(700, null)
    }

    /** Remembers the sessions it was asked to close, and counts saves and lookups; [kept] is what every reuse lookup finds. */
    private class RecordingStore : HistoryStore {
        val deleted: MutableList<String> = Collections.synchronizedList(mutableListOf())
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
        override fun revisions(): Flow<Long> = flowOf(0L)
        override suspend fun page(before: com.evanaronson.linguize.history.HistoryCursor?, limit: Int) =
            com.evanaronson.linguize.history.HistoryPage(emptyList(), null)
        override suspend fun detail(id: String): SessionDetail? = null
        override suspend fun delete(id: String): Boolean { deleted += id; return true }
        override suspend fun clear() = Unit
        override suspend fun export(out: OutputStream) = true
    }
}
