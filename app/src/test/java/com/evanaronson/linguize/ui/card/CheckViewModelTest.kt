package com.evanaronson.linguize.ui.card

import android.app.Activity
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
import com.evanaronson.linguize.core.WritingProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.fakeChecker
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.CheckHistory
import com.evanaronson.linguize.history.HistoryEnvironment
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Opening
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.ReuseKey
import com.evanaronson.linguize.history.SessionContext
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionDetailResult
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
import org.robolectric.android.controller.ActivityController
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
    private val retiredExports = AtomicInteger()
    private val history = CheckHistory(store, environment, scope, retireExports = { retiredExports.incrementAndGet() })
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

    class ResultCaller : Activity() {
        lateinit var request: Intent
        var receivedResult: Triple<Int, Int, Intent?>? = null

        @Deprecated("Activity result test boundary")
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            receivedResult = Triple(requestCode, resultCode, data)
        }
    }

    class ResultForwarder : Activity()

    /** Robolectric records launches but needs the platform's result recipient and dispatch bridged. */
    @Suppress("DEPRECATION")
    private fun launchSelection(
        caller: ResultCaller,
        readOnly: Boolean? = null,
        forResult: Boolean = true,
        forwarded: Boolean = false,
    ): ActivityController<CheckActivity> {
        val intent = Intent(caller, CheckActivity::class.java)
            .setAction(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, QUESTION)
        readOnly?.let { intent.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, it) }
        caller.request = if (forwarded) Intent(caller, ResultForwarder::class.java) else intent
        if (forResult) caller.startActivityForResult(caller.request, 42) else caller.startActivity(caller.request)
        val launched = shadowOf(caller).nextStartedActivityForResult
        assertEquals(if (forResult) 42 else -1, launched.requestCode)
        val selectionIntent = if (forwarded) {
            val forwarder = Robolectric.buildActivity(ResultForwarder::class.java, launched.intent).setup()
            shadowOf(forwarder.get()).setCallingActivity(caller.componentName)
            forwarder.get().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT))
            val forwardedIntent = shadowOf(forwarder.get()).nextStartedActivity
            forwarder.pause().stop().destroy()
            forwardedIntent
        } else launched.intent
        val controller = Robolectric.buildActivity(CheckActivity::class.java, selectionIntent)
        val activity = controller.get()
        if (forResult) shadowOf(activity).setCallingActivity(caller.componentName)
        activity.viewModelStore.put(
            "androidx.lifecycle.ViewModelProvider.DefaultKey:" + CheckViewModel::class.java.canonicalName,
            check,
        )
        return controller.create().start().resume()
    }

    private fun deliverSelectionResult(caller: ResultCaller, activity: CheckActivity) {
        shadowOf(caller).receiveResult(caller.request, shadowOf(activity).resultCode, shadowOf(activity).resultIntent)
        assertEquals(42, caller.receivedResult?.first)
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
        assertEquals(1, retiredExports.get())
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
        assertEquals(0, retiredExports.get())
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
        runBlocking { history.flush() }
        assertTrue(store.deleted.isEmpty())
        assertEquals(0, retiredExports.get())
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
        val session = closed().single()
        assertEquals(Outcome.None, session.outcome)
        assertNull(session.finalText)
        assertEquals(Decision.Undone, store.suggestions.single { it.kind == EditKind.Fix }.decision)
        assertFalse(store.suggestions.any { it.decision == Decision.Copied })
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
    fun removingOneChoiceRetractsOnlyThatFactAndPreservesEarlierAttemptEvidence() {
        val first = Settled("tu", "informal")
        val other = Settled("When?", "Tomorrow")
        val assumption = Assumption(first.about, "formal", alternatives = listOf(first.answer))
        client.answer = { request ->
            val verdict = QUESTION_VERDICT.copy(assumptions =
                if (request.settled.any { it.about == first.about }) emptyList() else listOf(assumption))
            ModelAnswer(verdict, Json.encodeToString(verdict))
        }
        check.check(QUESTION, null, Origin.Menu)
        reviewed()
        check.acceptAll(EditKind.Fix)
        check.settle(first.about, first.answer)
        reviewed()
        check.settle(other.about, other.answer)
        assertEquals(listOf(first, other), done().settled)

        val context = ApplicationProvider.getApplicationContext<Application>()
        val actions = CardActions.of(check, context, onClose = {}, onOpenSettings = null)
        actions.onRemoveChoice!!(first.about)
        val result = reviewed()
        val request = client.checks.last().third
        assertEquals(4, client.checks.size)
        assertEquals(QUESTION, request.text)
        assertEquals(listOf(other), request.settled)
        assertEquals(listOf(other), done().settled)
        assertEquals(1, result.revision.acceptedCount)
        val message = Prompt("instructions", "{}").userMessage(request)
        assertFalse(message.contains("\"about\":\"${first.about}\""))
        assertFalse(message.contains(first.answer))
        assertTrue(message.contains("Settled: {\"about\":\"${other.about}\",\"answer\":\"${other.answer}\"}"))
        // This fake returned a question again. It uses the existing clarification path;
        // removing a choice does not guarantee a provider will return that question.
        assertEquals(listOf(assumption), linkedAssumptions(listOf(0), result.assumptions, done().settled))
        check.settle(assumption.about, assumptionChoices(assumption).last())
        assertEquals(listOf(other, first), done().settled)
        check.dismiss(applied = false)
        assertEquals(
            listOf(emptyList(), listOf(first), listOf(first, other), listOf(other), listOf(other, first)),
            closed().single().attempts.map { it.settled },
        )
    }

    @Test
    fun retryAfterRemovingAChoiceFailsKeepsTheReducedAnswersAndAcceptedChanges() {
        val first = Settled("tu", "informal")
        val other = Settled("When?", "Tomorrow")
        check.check(QUESTION, null, Origin.Menu)
        reviewed()
        check.acceptAll(EditKind.Fix)
        check.settle(first.about, first.answer)
        reviewed()
        check.settle(other.about, other.answer)
        reviewed()
        client.answer = { throw CheckFailure(CheckFailure.Reason.Offline) }
        check.removeChoice(first.about)
        waitFor("failed removal recheck") { check.state is CardState.Failed }
        assertEquals(listOf(other), client.checks.last().third.settled)
        assertEquals("Com estàs?", check.workingText)
        client.answer = { ModelAnswer(QUESTION_VERDICT, QUESTION_JSON) }
        check.recheck()
        assertEquals(1, reviewed().revision.acceptedCount)
        assertEquals(listOf(other), done().settled)
        assertEquals(listOf(other), client.checks.last().third.settled)
        check.dismiss(applied = false)
        val attempts = closed().single().attempts
        assertEquals(listOf(first, other), attempts[2].settled)
        assertEquals(listOf(other), attempts[3].settled)
        assertEquals(CheckFailure.Reason.Offline.token, attempts[3].failure)
        assertEquals(listOf(other), attempts[4].settled)
    }

    @Test
    fun dismissalCancelsTheRemovalRecheck() {
        check.check(QUESTION, null, Origin.Menu)
        reviewed()
        check.settle("tu", "informal")
        reviewed()
        val cancelled = AtomicBoolean(false)
        client.answer = {
            try { awaitCancellation() } finally { cancelled.set(true) }
        }
        check.removeChoice("tu")
        waitFor("removal request") { client.checks.size == 3 }
        check.dismiss(applied = false)
        waitFor("cancelled removal request") { cancelled.get() }
        assertNull(check.state)
        assertEquals(listOf(emptyList(), listOf(Settled("tu", "informal"))),
            closed().single().attempts.map { it.settled })
    }

    @Test
    fun aNewCheckCancelsTheRemovalRecheckAndStartsWithoutAnswers() {
        check.check(QUESTION, null, Origin.Menu)
        reviewed()
        check.settle("tu", "informal")
        reviewed()
        val cancelled = AtomicBoolean(false)
        client.answer = {
            try { awaitCancellation() } finally { cancelled.set(true) }
        }
        check.removeChoice("tu")
        waitFor("removal request") { client.checks.size == 3 }
        client.answer = { ModelAnswer(QUESTION_VERDICT, QUESTION_JSON) }
        check.check(QUESTION, null, Origin.Menu)
        assertTrue(done().settled.isEmpty())
        assertTrue(client.checks.last().third.settled.isEmpty())
        waitFor("cancelled removal request") { cancelled.get() }
        assertEquals(4, client.checks.size)
        assertEquals(listOf(emptyList(), listOf(Settled("tu", "informal"))),
            closed().single().attempts.map { it.settled })
    }

    @Test
    fun undoDuringClarificationCannotBeResurrectedByTheMatchingAnswer() {
        val original = "I saw her duck."
        val verdict = Verdict(Verdict.Status.Ok, language = "English", assumptions = listOf(
            Assumption("Meaning of duck", "She lowered her head", alternatives = listOf("Her bird"))),
            moreNatural = true, natural = "I saw her lower her head.", naturalChanges = listOf(
                VerdictChange("her duck", "her lower her head", confidence = 1, impact = 3, assumptionIndices = listOf(0))))
        client.answer = { ModelAnswer(verdict, Json.encodeToString(verdict)) }
        check.check(original, null, Origin.Menu)
        reviewed()
        check.acceptAll(EditKind.Natural)
        val accepted = check.workingText
        assertEquals("I saw her lower her head.", accepted)
        val answer = CompletableDeferred<Unit>()
        client.answer = {
            answer.await()
            // Even a response repeating the old interpretation cannot restore an undone edit.
            ModelAnswer(verdict, Json.encodeToString(verdict))
        }
        try {
            check.settle("Meaning of duck", "Her bird")
            waitFor("clarification request") { client.checks.size == 2 }
            assertEquals(listOf(Settled("Meaning of duck", "Her bird")), client.checks.last().third.settled)
            val loading = check.state as CardState.Loading
            assertEquals(accepted, loading.retainedRevision?.workingText)
            assertTrue(loading.retainedRevision!!.canUndo)
            check.undo()
            assertTrue(check.state is CardState.Loading)
            assertFalse(check.state!!.retainedRevision!!.canUndo)
            assertNull(check.workingText)
        } finally {
            answer.complete(Unit)
        }
        assertEquals(0, reviewed().revision.acceptedCount)
        assertNull(check.workingText)
        check.dismiss(applied = true)
        assertNull(closed().single().finalText)
        assertFalse(store.suggestions.any { it.decision == Decision.Accepted })
    }

    @Test
    fun retainedUndoPreservesSeparateAcceptanceActionsAndFailedHistory() {
        val verdict = QUESTION_VERDICT.copy(moreNatural = true, natural = "Com et trobes?",
            naturalChanges = listOf(VerdictChange("estàs", "et trobes", confidence = 1, impact = 3)))
        client.answer = { ModelAnswer(verdict, Json.encodeToString(verdict)) }
        check.check(QUESTION, null, Origin.Button)
        reviewed()
        check.acceptAll(EditKind.Fix)
        check.acceptAll(EditKind.Natural)
        assertEquals("Com et trobes?", check.workingText)
        client.answer = { throw CheckFailure(CheckFailure.Reason.Offline, "offline") }
        check.recheck()
        waitFor("failed recheck") { check.state is CardState.Failed }
        assertEquals("Com et trobes?", check.state!!.retainedRevision?.workingText)
        check.undo()
        assertTrue(check.state is CardState.Failed)
        assertEquals("Com estàs?", check.workingText)
        assertTrue(check.state!!.retainedRevision!!.canUndo)
        check.dismiss(applied = true)
        val session = closed().single()
        assertEquals("Com estàs?", session.finalText)
        assertEquals(Outcome.Applied, session.outcome)
        assertEquals("offline", session.attempts.last().failure)
        assertEquals(Decision.Accepted, store.suggestions.single { it.kind == EditKind.Fix }.decision)
        assertEquals(Decision.Undone, store.suggestions.single { it.kind == EditKind.Natural }.decision)
    }

    @Test
    @Config(application = App::class)
    fun undoAfterAFailedRecheckLeavesNothingForTheSelectionHostToReturn() {
        check.check(QUESTION, null, Origin.Menu)
        reviewed()
        check.acceptAll(EditKind.Fix)
        client.answer = { throw CheckFailure(CheckFailure.Reason.Offline, "offline") }
        check.recheck()
        waitFor("failed recheck") { check.state is CardState.Failed }
        val caller = Robolectric.buildActivity(ResultCaller::class.java).setup()
        val controller = launchSelection(caller.get())
        val activity = controller.get()
        CardActions.of(check, activity, onClose = {}, onOpenSettings = null).review!!.onUndo()
        assertNull(check.workingText)
        activity.finish()
        assertEquals(android.app.Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        val session = closed().single()
        assertNull(session.finalText)
        assertEquals(Outcome.Failed, session.outcome)
        assertEquals(Decision.Undone, store.suggestions.single().decision)
        deliverSelectionResult(caller.get(), activity)
        assertEquals(Activity.RESULT_CANCELED, caller.get().receivedResult?.second)
        controller.pause().stop().destroy()
        caller.pause().stop().destroy()
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
    fun choosingTheCurrentLanguageKeepsTheOpenReviewAndItsHistory() {
        check.check(QUESTION, Language.Catalan, Origin.Button)
        reviewed()
        check.settle("tu", "informal")
        reviewed()
        check.acceptAll(EditKind.Fix)
        val acceptedText = check.workingText
        val requestCount = client.checks.size

        check.changeLanguage(Language.Catalan)

        assertEquals(acceptedText, check.workingText)
        assertEquals(1, reviewed().revision.acceptedCount)
        assertEquals(listOf(Settled("tu", "informal")), done().settled)
        assertEquals(requestCount, client.checks.size)
        check.dismiss(applied = true)
        val session = closed().single()
        assertEquals("Com estàs?", session.finalText)
        assertEquals(listOf("ca", "ca"), session.attempts.map { it.requestedLanguage })
    }

    @Test
    fun selectingAutoAgainLeavesItsPendingRequestRunning() {
        val cancelled = AtomicBoolean(false)
        client.answer = {
            try { awaitCancellation() } finally { cancelled.set(true) }
        }
        check.check(QUESTION, null, Origin.Button)
        waitFor("the Auto request") { client.checks.size == 1 }

        check.changeLanguage(null)
        settle()

        assertFalse(cancelled.get())
        assertEquals(1, client.checks.size)
        check.dismiss(applied = false)
        waitFor("cancelled Auto request") { cancelled.get() }
    }

    @Test
    fun closingWhileTheNewLanguagesCheckerIsAcquiredSupersedesTheOldReview() {
        val acquiring = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val acquisitions = AtomicInteger()
        val card = CheckViewModel({
            if (acquisitions.incrementAndGet() == 2) {
                acquiring.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }
            checker
        }, history)
        viewModels.put("language-acquisition", card)
        try {
            card.check(QUESTION, Language.Catalan, Origin.Menu)
            waitFor("first answer") { card.state is CardState.Done }
            card.acceptAll(EditKind.Fix)
            assertEquals("Com estàs?", card.workingText)
            card.copied(EditKind.Fix)

            card.changeLanguage(Language.Spanish)
            assertTrue(card.state is CardState.Loading)
            assertNull(card.workingText)
            waitFor("second checker acquisition") { acquiring.count == 0L }
            card.dismiss(applied = false)

            val session = closed().single()
            assertNull(session.finalText)
            assertEquals(Outcome.Copied, session.outcome)
            // Selection alone does not claim that an attempt ran in the selected language.
            assertEquals("ca", session.requestedLanguage)
            assertEquals(1, session.attempts.size)
            assertTrue(session.attempts.single().replayInvalidated)
            assertTrue(store.suggestions.isNotEmpty())
            assertTrue(store.suggestions.all { it.decision == Decision.Superseded })
            assertEquals(1, client.checks.size)
        } finally {
            resume.countDown()
        }
    }

    @Test
    fun failedNewLanguageContextRecordsTheSelectionWithLastKnownSettings() {
        val failRead = AtomicBoolean(false)
        val preferences = object : CheckPreferences by this.preferences {
            override val punctuation: Punctuation
                get() {
                    if (failRead.get()) throw CheckFailure(CheckFailure.Reason.BadResponse, "context unavailable")
                    return this@CheckViewModelTest.preferences.punctuation
                }
        }
        val checker = Checker(preferences, { "key" }, Prompt("instructions", "{}"), Provider.entries.associateWith { client })
        val card = CheckViewModel({ checker }, history)
        viewModels.put("language-context-failure", card)
        card.check(QUESTION, Language.Catalan, Origin.Menu)
        waitFor("first answer") { card.state is CardState.Done }
        card.acceptAll(EditKind.Fix)

        this.preferences.punctuation = Punctuation.Strict
        failRead.set(true)
        card.changeLanguage(Language.Spanish)
        waitFor("context failure") { card.state is CardState.Failed }
        assertNull(card.workingText)
        card.dismiss(applied = false)

        val session = closed().single()
        assertNull(session.finalText)
        assertEquals(listOf("ca", "es"), session.attempts.map { it.requestedLanguage })
        assertEquals("bad_response", session.attempts.last().failure)
        // The attempted context was unavailable: only the previously acquired settings are known.
        assertEquals(listOf("moderate", "moderate"), session.attempts.map { it.punctuation })
        assertEquals(1, client.checks.size)
        assertTrue(store.suggestions.isNotEmpty())
        assertTrue(store.suggestions.all { it.attempt == 0 && it.decision == Decision.Superseded })
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
    fun backFromSettingsBeforeInitialContextPublicationRecordsOnlyTheLatestAttempt() =
        withInitialContextPaused { card, resume ->
            preferences.punctuation = Punctuation.Strict
            card.recheckIfStale()
            waitFor("latest settings answer") { card.state is CardState.Done }
            resume()
            assertEquals(Punctuation.Strict, client.checks.single().third.punctuation)
            card.dismiss(applied = false)

            val session = closed().single()
            assertEquals("org.telegram.messenger", session.hostApp)
            assertEquals(listOf("strict"), session.attempts.map { it.punctuation })
            assertEquals(QUESTION_JSON, session.attempts.single().raw)
            assertEquals(2, store.saves.get())
            assertEquals(0, store.lookups.get())
        }

    @Test
    fun backFromSettingsBeforeInitialContextPublicationNeverRecordsTheTester() =
        withInitialContextPaused(origin = Origin.Tester) { card, resume ->
            preferences.punctuation = Punctuation.Strict
            card.recheckIfStale()
            waitFor("tester answer with latest settings") { card.state is CardState.Done }
            resume()
            card.dismiss(applied = false)

            assertEquals(Punctuation.Strict, client.checks.single().third.punctuation)
            assertTrue(closed().isEmpty())
            assertEquals(0, store.saves.get())
            assertEquals(0, store.lookups.get())
        }

    @Test
    fun anInitialContextRefreshRefusedRecordingStaysUnrecordedAfterEnablingHistory() {
        val recordingEnabled = AtomicBoolean(false)
        val environment = object : HistoryEnvironment by this.environment {
            override val enabled get() = recordingEnabled.get()
        }
        val history = CheckHistory(store, environment, scope)
        withInitialContextPaused(history) { card, resume ->
            preferences.punctuation = Punctuation.Strict
            card.recheckIfStale()
            waitFor("unrecorded settings refresh") { card.state is CardState.Done }
            recordingEnabled.set(true)
            preferences.punctuation = Punctuation.Moderate
            card.recheckIfStale()
            waitFor("another unrecorded answer") { card.state is CardState.Done && client.checks.size == 2 }
            resume()
            card.dismiss(applied = false)
            history.awaitWrites(5_000)

            assertEquals(listOf(Punctuation.Strict, Punctuation.Moderate), client.checks.map { it.third.punctuation })
            assertTrue(store.closed.isEmpty())
            assertEquals(0, store.saves.get())
            assertEquals(0, store.lookups.get())
        }
    }

    @Test
    fun anInitialContextRefreshCannotResumeRevokedRecording() {
        val epoch = AtomicInteger()
        val environment = object : HistoryEnvironment by this.environment {
            override val recordingEpoch get() = epoch.get().toLong()
        }
        val history = CheckHistory(store, environment, scope)
        withInitialContextPaused(history) { card, resume ->
            preferences.punctuation = Punctuation.Strict
            card.recheckIfStale()
            waitFor("recorded settings refresh") { card.state is CardState.Done }
            history.awaitWrites(5_000)
            assertEquals(2, store.saves.get())

            // Turning recording off and on advances its epoch, even if no write saw it off.
            epoch.incrementAndGet()
            repeat(2) { retry ->
                card.recheck()
                waitFor("answer after revoking recording") {
                    card.state is CardState.Done && client.checks.size == retry + 2
                }
            }
            resume()
            card.dismiss(applied = false)
            history.awaitWrites(5_000)

            assertEquals(2, store.saves.get())
            assertTrue(store.closed.isEmpty())
            assertEquals(0, store.lookups.get())
        }
    }

    @Test
    fun anInitialContextAndItsSettingsRefreshCannotReopenADismissedCard() =
        withInitialContextPaused(pauseEveryRead = true) { card, resume ->
            preferences.punctuation = Punctuation.Strict
            card.recheckIfStale()
            card.dismiss(applied = false)
            resume()

            assertNull(card.state)
            assertTrue(client.checks.isEmpty())
            assertTrue(closed().isEmpty())
            assertEquals(0, store.saves.get())
        }

    @Test
    fun anInitialContextAndItsSettingsRefreshCannotReplaceANewerCard() =
        withInitialContextPaused(pauseEveryRead = true) { card, resume ->
            preferences.punctuation = Punctuation.Strict
            card.recheckIfStale()
            client.answer = { ModelAnswer(Verdict(Verdict.Status.Ok), """{"status":"ok"}""") }
            card.check("New selection", Language.Spanish, Origin.Menu)
            assertTrue(card.state is CardState.Loading)
            resume()
            waitFor("replacement card answer") { card.state is CardState.Done }

            assertEquals("New selection", client.checks.single().third.text)
            assertEquals(Language.Spanish, client.checks.single().third.language)
            card.dismiss(applied = false)
            assertEquals("New selection", closed().single().text)
        }

    /** Holds the first complete settings snapshot in IO, before the view model can publish it. */
    private fun withInitialContextPaused(
        history: CheckHistory = this.history,
        origin: Origin = Origin.Menu,
        pauseEveryRead: Boolean = false,
        test: (CheckViewModel, resume: () -> Unit) -> Unit,
    ) {
        val firstRead = AtomicBoolean(true)
        val reading = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val preferences = object : CheckPreferences by this.preferences {
            override val writingProfiles: List<WritingProfile>
                get() {
                    // This is the final preference read: all settings have already been captured.
                    val value = this@CheckViewModelTest.preferences.writingProfiles
                    if (firstRead.compareAndSet(true, false) || pauseEveryRead) {
                        reading.countDown()
                        try {
                            check(resume.await(5, TimeUnit.SECONDS))
                        } finally {
                            returned.countDown()
                        }
                    }
                    return value
                }
        }
        val checker = Checker(preferences, { "key" }, Prompt("instructions", "{}"), Provider.entries.associateWith { client })
        val card = CheckViewModel({ checker }, history)
        viewModels.put("initial-context-race", card)
        try {
            card.check(QUESTION, null, origin, "org.telegram.messenger")
            waitFor("initial context snapshot") { reading.count == 0L }
            assertTrue(card.state is CardState.Loading)
            assertTrue(client.checks.isEmpty())
            assertEquals(0, store.saves.get())
            test(card) {
                resume.countDown()
                waitFor("initial context read to return") { returned.count == 0L }
                settle()
            }
        } finally {
            resume.countDown()
        }
    }

    @Test
    fun backFromSettingsDuringHistoryOpeningRecordsTheSuccessfulRetry() {
        val firstRead = AtomicBoolean(true)
        val reading = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val environment = object : HistoryEnvironment {
            override val enabled = true
            override val appVersion = "1.0"
            override val deviceId: String
                get() {
                    if (firstRead.compareAndSet(true, false)) {
                        reading.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                    }
                    return "device"
                }
        }
        val history = CheckHistory(store, environment, scope)
        val card = CheckViewModel({ checker }, history)
        viewModels.put("opening-retry", card)
        client.answer = { request ->
            if (request.punctuation == Punctuation.Moderate) awaitCancellation()
            ModelAnswer(QUESTION_VERDICT, QUESTION_JSON)
        }
        try {
            card.check(QUESTION, null, Origin.Menu, "org.telegram.messenger")
            waitFor("history environment read") { reading.count == 0L }
            preferences.punctuation = Punctuation.Strict
            card.recheckIfStale()
            waitFor("retry answer") { card.state is CardState.Done }
            resume.countDown()
            settle()
            card.dismiss(applied = false)
            history.awaitWrites(5_000)

            assertEquals(2, client.checks.size)
            assertEquals(0, store.lookups.get())
            assertEquals(2, store.saves.get())
            val session = store.closed.single()
            assertEquals("org.telegram.messenger", session.hostApp)
            assertEquals(listOf("strict"), session.attempts.map { it.punctuation })
            assertEquals(QUESTION_JSON, session.attempts.single().raw)
            assertEquals(Outcome.None, session.outcome)
        } finally {
            resume.countDown()
        }
    }

    @Test
    fun replacingAKeyDuringLoadingRetriesAndRejectsTheLateOldKeyFailure() {
        val revisions = Provider.entries.associateWith { 0L }.toMutableMap()
        val keys = Collections.synchronizedMap(mutableMapOf(Provider.Gemini to "old-key"))
        val checker = fakeChecker(preferences, client, keys)
        val card = CheckViewModel({ checker }, history, keyRevision = { revisions.getValue(it) })
        viewModels.put("key-replacement", card)
        val oldFailure = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        client.answer = {
            if (requests.incrementAndGet() == 1) withContext(NonCancellable) {
                oldFailure.await()
                throw CheckFailure(CheckFailure.Reason.BadKey)
            }
            ModelAnswer(QUESTION_VERDICT, QUESTION_JSON)
        }
        val storedSettings = runBlocking { checker.context().stored }
        try {
            card.check(QUESTION, null, Origin.Button)
            waitFor("old-key request") { requests.get() == 1 }
            card.recheckIfStale()
            settle()
            assertEquals(1, requests.get())
            revisions[Provider.OpenAI] = 1L
            card.recheckIfStale()
            settle()
            assertEquals(1, requests.get())
            assertTrue(card.state is CardState.Loading)

            keys[Provider.Gemini] = "new-key"
            revisions[Provider.Gemini] = 1L
            card.recheckIfStale()
            waitFor("new-key answer") { card.state is CardState.Done }
            assertEquals(listOf("old-key", "new-key"), client.checks.map { it.first })
            oldFailure.complete(Unit)
            settle()
            assertTrue(card.state is CardState.Done)

            revisions[Provider.Gemini] = 2L
            card.recheckIfStale()
            settle()
            assertEquals(2, requests.get())
            assertEquals(storedSettings, runBlocking { checker.context().stored })
            card.dismiss(applied = false)
            val session = closed().single()
            assertEquals(1, session.attempts.size)
            assertNull(session.attempts.single().failure)
            assertEquals(QUESTION_JSON, session.attempts.single().raw)
        } finally { oldFailure.complete(Unit) }
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
        val caller = Robolectric.buildActivity(ResultCaller::class.java).setup()
        val controller = launchSelection(caller.get())
        val activity = controller.get()
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
        deliverSelectionResult(caller.get(), activity)
        assertEquals(Activity.RESULT_OK, caller.get().receivedResult?.second)
        assertEquals("Com estàs?", caller.get().receivedResult?.third?.getStringExtra(Intent.EXTRA_PROCESS_TEXT))
        assertEquals(Outcome.Applied, closed().single().outcome)
        controller.pause().stop().destroy()
        caller.pause().stop().destroy()
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
    fun aForwardedSelectionReturnsAcceptedEditsToTheOriginalRecipientOnDismissal() {
        check.check(QUESTION, null, Origin.Menu)
        reviewed()
        check.acceptAll(EditKind.Fix)
        val caller = Robolectric.buildActivity(ResultCaller::class.java).setup()
        val controller = launchSelection(caller.get(), readOnly = false, forwarded = true)
        val activity = controller.get()
        assertTrue(activity.intent.flags and Intent.FLAG_ACTIVITY_FORWARD_RESULT != 0)
        assertEquals(caller.get().componentName, activity.callingActivity)
        activity.finish()
        deliverSelectionResult(caller.get(), activity)
        assertEquals(Activity.RESULT_OK, caller.get().receivedResult?.second)
        assertEquals("Com estàs?", caller.get().receivedResult?.third?.getStringExtra(Intent.EXTRA_PROCESS_TEXT))
        val session = closed().single()
        assertEquals("Com estàs?", session.finalText)
        assertEquals(Outcome.Applied, session.outcome)
        controller.pause().stop().destroy()
        caller.pause().stop().destroy()
    }

    @Test
    @Config(application = App::class)
    fun aReadOnlySelectionNeverReturnsAReplacementEvenWithAcceptedState() {
        check.check(QUESTION, null, Origin.Menu)
        reviewed()
        check.acceptAll(EditKind.Fix)
        val caller = Robolectric.buildActivity(ResultCaller::class.java).setup()
        val controller = launchSelection(caller.get(), readOnly = true)
        val activity = controller.get()
        activity.finish()
        assertEquals(android.app.Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        val session = closed().single()
        assertNull(session.finalText)
        assertEquals(Outcome.None, session.outcome)
        deliverSelectionResult(caller.get(), activity)
        assertEquals(Activity.RESULT_CANCELED, caller.get().receivedResult?.second)
        controller.pause().stop().destroy()
        caller.pause().stop().destroy()
    }

    @Test
    @Config(application = App::class)
    fun aSelectionStartedWithoutAResultRecipientNeverAppliesAcceptedState() {
        check.check(QUESTION, null, Origin.Menu)
        reviewed()
        check.acceptAll(EditKind.Fix)
        val caller = Robolectric.buildActivity(ResultCaller::class.java).setup()
        val controller = launchSelection(caller.get(), forResult = false)
        val activity = controller.get()
        assertNull(activity.callingActivity)
        activity.finish()
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertNull(shadowOf(activity).resultIntent)
        assertNull(caller.get().receivedResult)
        val session = closed().single()
        assertNull(session.finalText)
        assertEquals(Outcome.None, session.outcome)
        assertTrue(store.suggestions.none { it.decision == Decision.Accepted })
        controller.pause().stop().destroy()
        caller.pause().stop().destroy()
    }

    @Test
    @Config(application = App::class)
    fun aSelectionWithoutAResultRecipientKeepsItsCopiedOutcome() {
        check.check(QUESTION, null, Origin.Menu)
        val corrected = reviewed().revision.preview(EditKind.Fix).text
        val caller = Robolectric.buildActivity(ResultCaller::class.java).setup()
        val controller = launchSelection(caller.get(), forResult = false, readOnly = false)
        val activity = controller.get()
        val actions = CardActions.of(check, activity, onClose = activity::finish,
            onOpenSettings = null, canReplace = false)
        assertNull(actions.review)
        actions.onCopy(corrected, EditKind.Fix)
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        assertEquals(corrected, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertFalse(activity.isFinishing)
        activity.finish()
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        val session = closed().single()
        assertNull(session.finalText)
        assertEquals(Outcome.Copied, session.outcome)
        controller.pause().stop().destroy()
        caller.pause().stop().destroy()
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
        check.removeChoice("tu")
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
        val suggestions: MutableList<SuggestionRecord> = Collections.synchronizedList(mutableListOf())
        val saves = AtomicInteger()
        val lookups = AtomicInteger()

        @Volatile var kept: SessionDetail? = null

        override suspend fun save(session: SessionRecord) {
            saves.incrementAndGet()
        }
        override suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>) {
            this.suggestions += suggestions
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
        override suspend fun detail(id: String) = SessionDetailResult.Missing
        override suspend fun delete(id: String): Boolean { deleted += id; return true }
        override suspend fun clear() = Unit
        override suspend fun export(out: OutputStream) = true
    }
}
