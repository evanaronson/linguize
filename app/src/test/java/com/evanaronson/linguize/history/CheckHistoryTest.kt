package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.interpret
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext

/** The history lifecycle of a card, against a store that only remembers what it was asked. */
class CheckHistoryTest {
    private val rain = "Com estas amb la pluja?"
    private val verdict = Verdict(
        status = Verdict.Status.Ok,
        hasErrors = true,
        corrected = "Com estàs amb la pluja?",
        moreNatural = true,
        natural = "Com portes la pluja?",
    )
    private val result = interpret(rain, verdict) as CheckResult.Reviewed

    private val settings = SessionSettings(
        nativeLanguage = "English",
        punctuation = "moderate",
        judgments = "fix",
        provider = "openai",
        model = "a-model",
        promptHash = "p1",
    )

    private val opening = Opening(origin = Origin.Menu, hostApp = "org.telegram.messenger", requestedLanguage = "ca", settings = settings)

    private val store = FakeStore()
    private val environment = object : HistoryEnvironment {
        @Volatile override var enabled = true
        @Volatile override var recordingEpoch = 0L
        override val deviceId = "device"
        override val appVersion = "1.0"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_000L
    private val logged: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    private var ids = 0
    private val history = CheckHistory(
        store = store,
        environment = environment,
        scope = scope,
        clock = { now },
        log = { message, _ -> logged += message },
        maxChars = 100,
        newId = { "id${ids++}" },
    )

    @After
    fun stop() = scope.cancel()

    @Test
    fun aCardStaysInMemoryUntilAppliedThenSavesOneCompleteTransaction() = runBlocking<Unit> {
        val opened = history.open(rain, opening)!!
        assertEquals("moderate", opened.punctuation)
        assertEquals("fix", opened.judgments)
        assertEquals("openai", opened.provider)
        assertEquals("device", opened.deviceId)
        assertEquals("1.0", opened.appVersion)
        assertEquals(1_000, opened.startedAt)

        now = 1_100
        history.succeeded(emptyList(), "{}", result)
        val fixed = result.revision.acceptAll(EditKind.Fix)
        history.changed(fixed)
        history.flush()
        assertTrue(store.calls.isEmpty())
        assertEquals(setOf(opened.id), store.registered.toSet())
        now = 1_200
        history.close(fixed.workingText)
        history.flush()

        assertEquals(listOf("close"), store.calls.map { it.first })
        val (_, closed, suggestions) = store.calls.last()
        assertEquals(Outcome.Applied, closed.outcome)
        assertEquals(Verdict.Status.Ok, closed.status)
        assertEquals(1, closed.attempts.size)
        assertEquals(Decision.Accepted, suggestions.single { it.kind == EditKind.Fix }.decision)
        assertEquals(fixed.workingText, closed.finalText)
        assertEquals(1_200L, closed.closedAt)
        assertTrue(store.registered.isEmpty())
        assertNull(history.session)
    }

    @Test
    fun discardDropsOnlyTheInMemorySession() = runBlocking<Unit> {
        history.open(rain, opening)
        history.succeeded(emptyList(), "{}", result)
        history.discard()
        history.recheck(rain, opening)
        history.succeeded(emptyList(), "{}", result)
        closeAfterApplyingFix()
        history.flush()
        assertNull(history.session)
        assertTrue(store.calls.isEmpty())
        assertTrue(store.deleted.isEmpty())
        assertTrue(store.registered.isEmpty())
    }

    @Test
    fun analyzingCopyingOrAcceptingWithoutApplyingNeverWritesHistory() = runBlocking<Unit> {
        val actions = listOf<Pair<String, () -> Unit>>(
            "analyzed" to {},
            "copied" to { history.copied(EditKind.Fix) },
            "accepted but not applied" to { history.changed(result.revision.acceptAll(EditKind.Fix)) },
        )
        for ((name, act) in actions) {
            history.open(rain, opening)
            history.succeeded(emptyList(), "{}", result)
            act()
            history.flush()
            assertTrue(name, store.calls.isEmpty())
            history.close(null)
            history.flush()
            assertTrue(name, store.calls.isEmpty())
            assertTrue(name, store.registered.isEmpty())
        }
    }

    @Test
    fun undoingBackToTheOriginalTextNeverWritesHistory() = runBlocking<Unit> {
        history.open(rain, opening)
        history.succeeded(emptyList(), "{}", result)
        val fixed = result.revision.acceptAll(EditKind.Fix)
        history.changed(fixed)
        val undone = fixed.undo()
        history.changed(undone)
        assertEquals(rain, undone.workingText)
        history.close(undone.workingText)
        history.flush()
        assertTrue(store.calls.isEmpty())
        assertTrue(store.registered.isEmpty())
    }

    @Test
    fun closingTwiceOrWithNothingOpenWritesNothingMore() = runBlocking<Unit> {
        history.close(null)
        history.open(rain, opening)
        history.succeeded(emptyList(), "{}", result)
        closeAfterApplyingFix()
        history.close(null)
        history.flush()
        assertEquals(listOf("close"), store.calls.map { it.first })
    }

    @Test
    fun withHistoryOffNothingIsWritten() = runBlocking<Unit> {
        environment.enabled = false
        assertNull(history.open(rain, opening))
        history.succeeded(emptyList(), "{}", result)
        closeAfterApplyingFix()
        history.flush()
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun turningHistoryOffStopsRecordingTheOpenSession() = runBlocking<Unit> {
        history.open(rain, opening)
        history.flush()
        environment.enabled = false
        history.succeeded(emptyList(), "{}", result)
        environment.enabled = true
        history.changed(result.revision.acceptAll(EditKind.Fix))
        closeAfterApplyingFix()
        history.flush()
        assertTrue(store.calls.isEmpty())
        assertTrue(store.registered.isEmpty())
        assertNull(history.session)
    }

    @Test
    fun turningHistoryOffAndOnWhileTheCardIsIdleNeverResumesItsRecording() = runBlocking<Unit> {
        history.open(rain, opening)
        history.flush()
        environment.enabled = false
        environment.recordingEpoch++
        environment.enabled = true

        history.succeeded(emptyList(), "{}", result)
        closeAfterApplyingFix()
        history.flush()
        assertTrue(store.calls.isEmpty())

        // A genuinely new card has the new permission and can be recorded.
        history.open("new text", opening)
        history.close("changed text")
        history.flush()
        assertEquals(listOf("close"), store.calls.map { it.first })
    }

    @Test
    fun disablingHistoryAlsoRevokesWritesAlreadyWaitingInTheQueue() = runBlocking<Unit> {
        val dispatcher = QueuedDispatcher()
        val writing = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val queued = CheckHistory(store, environment, writing)
            queued.open(rain, opening)
            queued.succeeded(emptyList(), "{}", result)
            closeAfterApplyingFix(queued)

            environment.enabled = false
            dispatcher.runAll()
            queued.flush()
            assertTrue(store.calls.isEmpty())
            assertTrue(store.registered.isEmpty())
        } finally {
            writing.cancel()
        }
    }

    @Test
    fun aNewCardCannotReviveAnOldCardsQueuedWritesAfterRecordingWasDisabled() = runBlocking<Unit> {
        val dispatcher = QueuedDispatcher()
        val writing = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val queued = CheckHistory(store, environment, writing)
            queued.open(rain, opening)
            queued.succeeded(emptyList(), "{}", result)
            closeAfterApplyingFix(queued)
            environment.enabled = false
            environment.recordingEpoch++
            environment.enabled = true
            queued.open("new text", opening)
            queued.close("changed text")

            dispatcher.runAll()
            queued.flush()
            assertEquals(listOf("close"), store.calls.map { it.first })
            assertTrue(store.calls.all { it.second.text == "new text" })
        } finally {
            writing.cancel()
        }
    }

    @Test
    fun disablingAndEnablingHistoryAfterAcceptanceStillPreventsTheFinalWrite() = runBlocking<Unit> {
        history.open(rain, opening)
        history.succeeded(emptyList(), "{}", result)
        val fixed = result.revision.acceptAll(EditKind.Fix)
        history.changed(fixed)
        environment.enabled = false
        environment.recordingEpoch++
        environment.enabled = true

        history.close(fixed.workingText)
        history.flush()
        assertTrue(store.calls.isEmpty())
        assertTrue(store.registered.isEmpty())
    }

    @Test
    fun appliedCardsKeepTheirWriteOrderAndSurviveALaterDiscard() = runBlocking<Unit> {
        val dispatcher = QueuedDispatcher()
        val writing = CoroutineScope(SupervisorJob() + dispatcher)
        val firstWrite = CompletableDeferred<Unit>()
        val entered = mutableListOf<String>()
        val delayed = object : HistoryStore by store {
            override suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>) {
                entered += session.text
                if (session.text == rain) firstWrite.await()
                store.close(session, suggestions)
            }
        }
        try {
            val queued = CheckHistory(delayed, environment, writing)
            val first = queued.open(rain, opening)!!
            queued.succeeded(emptyList(), "{}", result)
            closeAfterApplyingFix(queued)
            val second = queued.open("next text", opening)!!
            queued.close("changed text")
            queued.open("discarded", opening)
            queued.discard()

            dispatcher.runAll()
            assertEquals(listOf(rain), entered)
            assertTrue(store.calls.isEmpty())
            assertEquals(setOf(first.id, second.id), store.registered.toSet())
            firstWrite.complete(Unit)
            dispatcher.runAll()
            queued.flush()

            assertEquals(listOf(first.id, second.id), store.calls.map { it.second.id })
            assertTrue(store.calls.all { it.first == "close" })
            assertTrue(store.registered.isEmpty())
        } finally {
            writing.cancel()
        }
    }

    @Test
    fun aTextTooLongToCheckIsntRecorded() = runBlocking<Unit> {
        assertNull(history.open("x".repeat(101), opening))
        history.failed(emptyList(), "TooLong", null)
        history.close(null)
        history.flush()
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun applyingAfterFailedAttemptsKeepsAllAttemptsAndClarifications() = runBlocking<Unit> {
        history.open(rain, opening)
        history.failed(listOf(Settled("tu", "informal")), "Offline", "no network", message = "empty_reply")
        history.flush()
        assertTrue(store.calls.isEmpty())
        history.succeeded(listOf(Settled("tu", "informal")), "{}", result)
        history.changed(result.revision.acceptAll(EditKind.Fix))
        history.failed(listOf(Settled("tu", "formal")), "Offline", "no network")
        closeAfterApplyingFix()
        history.flush()
        val closed = store.calls.single().second
        assertEquals(Outcome.Applied, closed.outcome)
        assertEquals(3, closed.attempts.size)
        assertEquals("Offline", closed.attempts.first().failure)
        assertEquals(listOf(Settled("tu", "informal")), closed.attempts.first().settled)
        assertEquals("empty_reply", closed.attempts.first().failureMessage)
        assertEquals(listOf(Settled("tu", "formal")), closed.attempts.last().settled)
        assertEquals(Decision.Accepted, store.calls.single().third.single { it.kind == EditKind.Fix }.decision)
    }

    @Test
    fun aFailedCheckNeverWritesHistory() = runBlocking<Unit> {
        history.open(rain, opening)
        history.failed(emptyList(), "Offline", "no network")
        history.close(null)
        history.flush()
        assertTrue(store.calls.isEmpty())
        assertTrue(store.registered.isEmpty())
    }

    @Test
    fun aStoreThatFailsDoesntBreakTheCard() = runBlocking<Unit> {
        store.failing = true
        history.open(rain, opening)
        history.succeeded(emptyList(), "{}", result)
        closeAfterApplyingFix()
        history.flush()
        assertEquals(1, logged.size)
        assertTrue(store.registered.isEmpty())
    }

    @Test
    fun theModelsAnswerIsUsedWhenThereIsNothingKept() = runBlocking<Unit> {
        val answer = history.firstAttempt(rain, opening, ask = { "model" }, reuse = { "kept" })
        assertEquals(Answer("model", null), answer)
    }

    @Test
    fun aKeptVerdictThatArrivesFirstIsShownAndTheRequestCancelled() = runBlocking<Unit> {
        store.kept = keptSession("earlier", "{\"status\":\"ok\"}")
        var cancelled = false
        val answer = history.firstAttempt(
            rain,
            opening,
            ask = {
                try {
                    delay(10_000)
                    "model"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelled = true
                    throw e
                }
            },
            reuse = { kept -> "kept ${kept.raw}" },
        )
        assertEquals("kept {\"status\":\"ok\"}", answer.value)
        assertEquals("earlier", answer.reused?.sessionId)
        assertEquals(listOf(Settled("tu", "informal")), answer.reused?.settled)
        assertTrue(cancelled)

        // The lookup asked for the same text and settings, from the last ten minutes.
        val (key, since) = store.lookedUp.single()
        assertEquals(SessionRecording.hash(rain), key.textHash)
        assertEquals("ca", key.requestedLanguage)
        assertEquals("English", key.settings.nativeLanguage)
        assertEquals("fix", key.settings.judgments)
        assertEquals(1_000 - CheckHistory.REUSE_WITHIN_MS, since)

        history.succeeded(answer.reused!!.settled, answer.reused!!.raw, result, reusedFrom = answer.reused!!.sessionId)
        closeAfterApplyingFix()
        history.flush()
        assertEquals("earlier", store.calls.last().second.attempts.single().reusedFrom)
    }

    @Test
    fun aSlowLookupNeverHoldsUpTheModelsAnswer() = runBlocking<Unit> {
        store.kept = keptSession("earlier", "{}")
        store.gate = CompletableDeferred()
        val answer = withTimeout(5_000) {
            history.firstAttempt(rain, opening, ask = { "model" }, reuse = { "kept" })
        }
        assertEquals(Answer("model", null), answer)
        store.gate!!.complete(Unit)
    }

    @Test
    fun aSlowReuseNeverHoldsUpTheModelsAnswer() = modelWinsDuringReuse()

    @Test
    fun aSlowReuseNeverHoldsUpTheModelsFailure() = modelWinsDuringReuse(IllegalStateException("offline"))

    private fun modelWinsDuringReuse(failure: IllegalStateException? = null) = runBlocking<Unit> {
        store.kept = keptSession("earlier", "{}")
        val model = CompletableDeferred<String>()
        val reuseStarted = CompletableDeferred<Unit>()
        val reuseGate = CompletableDeferred<Unit>()
        val reuseFinished = CompletableDeferred<Unit>()
        val pending = scope.async {
            history.firstAttempt(
                rain,
                opening,
                ask = { model.await() },
                reuse = {
                    // A non-cooperative loser must not hold up the live result or its caller.
                    withContext(NonCancellable) {
                        reuseStarted.complete(Unit)
                        reuseGate.await()
                        reuseFinished.complete(Unit)
                    }
                    "kept"
                },
            )
        }
        try {
            withTimeout(5_000) {
                reuseStarted.await()
                if (failure == null) model.complete("model") else model.completeExceptionally(failure)
                try {
                    assertEquals(Answer("model", null), pending.await())
                    assertNull(failure)
                    history.succeeded(emptyList(), "model", result)
                } catch (caught: IllegalStateException) {
                    // Coroutine stack recovery may copy the exception across suspension.
                    assertEquals(failure?.javaClass, caught.javaClass)
                    assertEquals(failure?.message, caught.message)
                    history.failed(emptyList(), "Offline", null)
                }
            }
            assertFalse(reuseGate.isCompleted)
            if (failure == null) closeAfterApplyingFix() else history.close(null)
            val next = history.open("next card", opening)!!
            reuseGate.complete(Unit)
            withTimeout(5_000) { reuseFinished.await() }
            history.flush()
            assertEquals(next.id, history.session!!.id)
            assertTrue(history.session!!.attempts.isEmpty())
            if (failure == null) {
                val closed = store.calls.single().second
                assertEquals(1, closed.attempts.size)
                assertNull(closed.attempts.single().reusedFrom)
            } else {
                assertTrue(store.calls.isEmpty())
            }
        } finally {
            reuseGate.complete(Unit)
            pending.cancelAndJoin()
        }
    }

    @Test
    fun aReadyModelAnswerStillWaitsForSessionPreparation() = runBlocking<Unit> {
        val dispatcher = QueuedDispatcher()
        val preparing = CheckHistory(store, environment, scope, io = dispatcher)
        var asked = false
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            preparing.firstAttempt(rain, opening, ask = { asked = true; "model" }, reuse = { "kept" })
        }
        assertTrue(asked)
        assertFalse(pending.isCompleted)
        assertNull(preparing.session)

        dispatcher.runAll()
        assertEquals(Answer("model", null), withTimeout(5_000) { pending.await() })
        // Recording ownership is ready before the caller records its result.
        preparing.succeeded(emptyList(), "model", result)
        assertEquals(1, preparing.session!!.attempts.size)
    }

    @Test
    fun aKeptAnswerCannotBeAcceptedAfterPermissionIsRevokedDuringReuse() = runBlocking<Unit> {
        store.kept = keptSession("earlier", "{}")
        val interpreted = CompletableDeferred<Unit>()
        val answer = history.firstAttempt(
            rain,
            opening,
            ask = { interpreted.await(); delay(100); "model" },
            reuse = {
                environment.enabled = false
                environment.recordingEpoch++
                environment.enabled = true
                interpreted.complete(Unit)
                "kept"
            },
        )
        assertEquals(Answer("model", null), answer)
    }

    @Test
    fun aReuseFailureIsLoggedAndFallsBackToTheModel() = runBlocking<Unit> {
        store.kept = keptSession("earlier", "{}")
        val failed = CompletableDeferred<Unit>()
        val logging = CheckHistory(store, environment, scope, log = { message, _ ->
            logged += message
            failed.complete(Unit)
        })
        val answer = logging.firstAttempt(
            rain,
            opening,
            ask = { failed.await(); "model" },
            reuse = { error("unreadable") },
        )
        assertEquals(Answer("model", null), answer)
        assertEquals(listOf("History: couldn't show a kept answer"), logged.toList())
    }

    @Test
    fun aKeptVerdictThatCantBeReadFallsBackToTheModel() = runBlocking<Unit> {
        store.kept = keptSession("earlier", "{}")
        val answer = history.firstAttempt(
            rain,
            opening,
            ask = {
                delay(200)
                "model"
            },
            reuse = { null },
        )
        assertEquals(Answer("model", null), answer)
    }

    @Test
    fun theModelsFailureIsThrownAsIs() = runBlocking<Unit> {
        try {
            history.firstAttempt<String>(rain, opening, ask = { throw IllegalStateException("offline") }, reuse = { "kept" })
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("offline", e.message)
        }
        assertTrue(history.session != null)
    }

    @Test
    fun withHistoryOffThereIsNoLookup() = runBlocking<Unit> {
        environment.enabled = false
        store.kept = keptSession("earlier", "{}")
        val answer = history.firstAttempt(
            rain,
            opening,
            ask = {
                delay(100)
                "model"
            },
            reuse = { "kept" },
        )
        assertEquals("model", answer.value)
        assertTrue(store.lookedUp.isEmpty())
        assertFalse(store.calls.isNotEmpty())
    }

    @Test
    fun aKeptSessionShowsItsDecidedAttemptsAnswer() = runBlocking<Unit> {
        // Answered, then a re-check came back unclear: the card's suggestions are the first answer's.
        val first = SessionRecording(rain, keptContext, 500, newId = { "earlier" })
        first.attempt(600, listOf(Settled("tu", "informal")), "{\"first\":1}", null, null, result.revision, null)
        first.attempt(650, emptyList(), "{\"second\":1}", null, null, null, null, Verdict.Status.Unclear)
        val fixed = result.revision.acceptAll(EditKind.Fix)
        first.changed(fixed)
        store.kept = first.close(700, fixed.workingText)
        val answer = history.firstAttempt(rain, opening, ask = { delay(10_000); "model" }, reuse = { it.raw })
        assertEquals("{\"first\":1}", answer.value)
        assertEquals(listOf(Settled("tu", "informal")), answer.reused?.settled)
    }

    @Test
    fun aRecheckWithOtherSettingsStaysInTheSessionAndRecordsThem() = runBlocking<Unit> {
        val first = history.open(rain, opening)!!
        history.succeeded(emptyList(), "{}", result)
        history.recheck(rain, opening)
        assertEquals(first.id, history.session?.id)

        now = 1_500
        history.recheck(rain, opening.copy(settings = settings.copy(model = "another-model", punctuation = "strict")))
        // Nothing changes until the attempt runs with them.
        assertEquals(first.id, history.session!!.id)
        assertEquals("a-model", history.session!!.model)
        history.succeeded(emptyList(), "{}", result)
        assertEquals("another-model", history.session!!.model)
        assertEquals("strict", history.session!!.punctuation)
        closeAfterApplyingFix()
        history.flush()

        assertEquals(listOf("close"), store.calls.map { it.first })
        val closed = store.calls.last().second
        assertEquals(first.id, closed.id)
        assertEquals(1_000, closed.startedAt)
        assertEquals("another-model", closed.model)
        assertEquals(listOf("a-model", "another-model"), closed.attempts.map { it.model })
        assertEquals(listOf("moderate", "strict"), closed.attempts.map { it.punctuation })
        assertEquals(listOf("ca", "ca"), closed.attempts.map { it.requestedLanguage })
        assertEquals(opening.settings, closed.reuseKeyOf(0)!!.settings)
        assertEquals(closed.reuseKey, closed.reuseKeyOf(1))
    }

    @Test
    fun aRecheckOfACardOpenedWithHistoryOffStaysUnrecorded() = runBlocking<Unit> {
        environment.enabled = false
        history.open(rain, opening)
        environment.enabled = true
        history.recheck(rain, opening.copy(settings = settings.copy(model = "another-model")))
        history.flush()
        assertNull(history.session)
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun aRecheckRecoversAfterRepeatedInterruptedOpenings() = runBlocking<Unit> {
        val dispatcher = QueuedDispatcher()
        val recovering = CheckHistory(store, environment, scope, io = dispatcher)
        val first = launch(start = CoroutineStart.UNDISPATCHED) { recovering.open(rain, opening) }
        first.cancel()
        dispatcher.runAll()
        first.join()

        repeat(2) {
            val retry = launch(start = CoroutineStart.UNDISPATCHED) { recovering.recheck(rain, opening) }
            retry.cancel()
            dispatcher.runAll()
            retry.join()
        }
        assertNull(recovering.session)
        assertTrue(store.calls.isEmpty())

        val changed = opening.copy(settings = settings.copy(model = "another-model"))
        val retry = launch(start = CoroutineStart.UNDISPATCHED) { recovering.recheck(rain, changed) }
        dispatcher.runAll()
        retry.join()
        recovering.succeeded(emptyList(), "{}", result)
        closeAfterApplyingFix(recovering)
        recovering.flush()

        assertEquals(listOf("close"), store.calls.map { it.first })
        val closed = store.calls.last().second
        assertEquals(1, store.calls.map { it.second.id }.distinct().size)
        assertEquals("another-model", closed.attempts.single().model)
        assertEquals(Outcome.Applied, closed.outcome)
        assertTrue(store.lookedUp.isEmpty())
    }

    @Test
    fun aRecheckCannotResumeRecordingAfterHistoryWasTurnedOffAndOn() = runBlocking<Unit> {
        history.open(rain, opening)
        history.succeeded(emptyList(), "{}", result)
        history.flush()
        environment.enabled = false
        environment.recordingEpoch++
        environment.enabled = true

        history.recheck(rain, opening)
        history.succeeded(emptyList(), "{}", result)
        assertNull(history.session)
        // The revoked permission cleared the recorder, but its card is still decided.
        history.recheck(rain, opening)
        history.succeeded(emptyList(), "{}", result)
        closeAfterApplyingFix()
        history.flush()

        assertTrue(store.calls.isEmpty())
        assertTrue(store.registered.isEmpty())
    }

    @Test
    fun aFailedAttemptKeepsTheAnswerThatCouldntBeRead() = runBlocking<Unit> {
        history.open(rain, opening)
        history.failed(emptyList(), "BadResponse", "not in the expected format", raw = "{\"status\":")
        history.succeeded(emptyList(), "{}", result)
        closeAfterApplyingFix()
        history.flush()
        val attempt = store.calls.last().second.attempts.first()
        assertEquals("{\"status\":", attempt.raw)
        assertEquals("BadResponse", attempt.failure)
    }

    @Test
    fun waitingForWritesBlocksUntilTheCloseIsWrittenButNotLonger() {
        runBlocking { history.open(rain, opening) }
        history.succeeded(emptyList(), "{}", result)
        closeAfterApplyingFix()
        history.awaitWrites(5_000)
        assertEquals(listOf("close"), store.calls.map { it.first })

        store.gate = CompletableDeferred()
        store.slowClose = true
        runBlocking { history.open(rain, opening) }
        history.succeeded(emptyList(), "{}", result)
        closeAfterApplyingFix()
        val started = System.nanoTime()
        history.awaitWrites(100)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        store.gate!!.complete(Unit)
    }

    @Test
    fun theTryItCardIsntRecorded() = runBlocking<Unit> {
        store.kept = keptSession("earlier", "{}")
        val tester = opening.copy(origin = Origin.Tester, hostApp = null)
        assertNull(history.open(rain, tester))
        val answer = history.firstAttempt(rain, tester, ask = { delay(100); "model" }, reuse = { "kept" })
        assertEquals(Answer("model", null), answer)
        history.succeeded(emptyList(), "{}", result)
        history.changed(result.revision.acceptAll(EditKind.Fix))
        history.copied(EditKind.Fix)
        history.changeLanguage("es")
        assertEquals(Answer("model", null), history.languageChanged(rain, tester.copy(requestedLanguage = "es"), ask = { delay(100); "model" }, reuse = { "kept" }))
        history.recheck(rain, tester.copy(settings = settings.copy(model = "another-model")))
        history.failed(emptyList(), "offline", null)
        closeAfterApplyingFix()
        history.flush()
        assertNull(history.session)
        assertTrue(store.lookedUp.isEmpty())
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun onlyChecksFromOtherAppsAreRecorded() {
        assertEquals(setOf(Origin.Menu, Origin.Button), Origin.entries.filter { it.recorded }.toSet())
    }

    @Test
    fun anotherLanguageIsTheSameSessionAskingForTheLastOne() = runBlocking<Unit> {
        // The model answers after the lookups have run, so each is recorded.
        val first = history.firstAttempt(rain, opening, ask = { slowly() }, reuse = { "kept" })
        assertEquals("model", first.value)
        val id = history.session!!.id
        now = 1_100
        history.succeeded(emptyList(), "{}", result)

        now = 1_200
        val spanish = opening.copy(requestedLanguage = "es")
        history.changeLanguage(spanish.requestedLanguage)
        assertEquals(Answer("model", null), history.languageChanged(rain, spanish, ask = { slowly() }, reuse = { "kept" }))
        assertEquals(id, history.session!!.id)
        now = 1_300
        history.succeeded(emptyList(), "{}", result)
        assertEquals("es", history.session!!.requestedLanguage)

        now = 1_400
        val auto = opening.copy(requestedLanguage = null)
        history.changeLanguage(null)
        history.languageChanged(rain, auto, ask = { slowly() }, reuse = { "kept" })
        now = 1_500
        history.succeeded(emptyList(), "{}", result)
        // A re-check in the language picked last is the same session too.
        history.recheck(rain, auto)
        assertEquals(id, history.session!!.id)
        history.flush()
        assertTrue(store.calls.isEmpty())
        closeAfterApplyingFix()
        history.flush()

        // All attempts and decisions are saved together after applying.
        assertEquals(listOf("close"), store.calls.map { it.first })
        val (_, closed, suggestions) = store.calls.last()
        assertEquals(id, closed.id)
        assertNull(closed.requestedLanguage)
        assertEquals(listOf("ca", "es", null), closed.attempts.map { it.requestedLanguage })
        assertEquals(Outcome.Applied, closed.outcome)
        // The first two languages' suggestions were replaced when the language changed.
        assertTrue(suggestions.filter { it.attempt < 2 }.all { it.decision == Decision.Superseded })
        assertEquals(setOf(1_200L, 1_400L), suggestions.filter { it.attempt < 2 }.map { it.decidedAt }.toSet())
        assertEquals(Decision.Accepted, suggestions.single { it.attempt == 2 && it.kind == EditKind.Fix }.decision)
        assertEquals(Decision.Ignored, suggestions.single { it.attempt == 2 && it.kind == EditKind.Natural }.decision)
        // Each language was looked up in its own.
        assertEquals(listOf("ca", "es", null), store.lookedUp.map { it.first.requestedLanguage })
        assertEquals(1_400 - CheckHistory.REUSE_WITHIN_MS, store.lookedUp.last().second)
    }

    @Test
    fun aKeptVerdictInTheNewLanguageIsShown() = runBlocking<Unit> {
        history.firstAttempt(rain, opening, ask = { "model" }, reuse = { "kept" })
        history.succeeded(emptyList(), "{}", result)
        store.kept = keptSession("earlier", "{\"status\":\"ok\"}", language = "es")
        history.changeLanguage("es")
        val answer = history.languageChanged(rain, opening.copy(requestedLanguage = "es"), ask = { delay(10_000); "model" }, reuse = { it.raw })
        assertEquals("{\"status\":\"ok\"}", answer.value)
        assertEquals("earlier", answer.reused?.sessionId)
        history.succeeded(answer.reused!!.settled, answer.reused!!.raw, result, reusedFrom = "earlier")
        closeAfterApplyingFix()
        history.flush()
        assertEquals(listOf(null, "earlier"), store.calls.last().second.attempts.map { it.reusedFrom })
    }

    @Test
    fun aKeptVerdictMadeInAnotherLanguageIsntShown() = runBlocking<Unit> {
        // Its card ended in Spanish, but the only answer it has was made in Catalan.
        val kept = SessionRecording(rain, keptContext, 500, newId = { "earlier" })
        kept.attempt(600, emptyList(), "{\"catalan\":1}", null, null, result.revision, null)
        kept.changeLanguage("es", 650)
        kept.attempt(660, emptyList(), null, "offline", null, null, null)
        store.kept = kept.close(700, null)
        val answer = history.firstAttempt(rain, opening.copy(requestedLanguage = "es"), ask = { delay(200); "model" }, reuse = { it.raw })
        assertEquals(Answer("model", null), answer)
    }

    @Test
    fun anotherLanguageWithOtherSettingsIsStillTheSameSession() = runBlocking<Unit> {
        val first = history.open(rain, opening)!!
        history.succeeded(emptyList(), "{}", result)
        val changed = opening.copy(requestedLanguage = "es", settings = settings.copy(model = "another-model"))
        history.changeLanguage(changed.requestedLanguage)
        history.languageChanged(rain, changed, ask = { slowly() }, reuse = { null })
        history.succeeded(emptyList(), "{}", result)
        assertEquals(first.id, history.session!!.id)
        assertEquals("es", history.session!!.requestedLanguage)
        assertEquals("another-model", history.session!!.model)
        // The lookup asked for the new language and settings.
        assertEquals(changed.settings, store.lookedUp.last().first.settings)
        assertEquals("es", store.lookedUp.last().first.requestedLanguage)
        closeAfterApplyingFix()
        history.flush()
        assertEquals(listOf("close"), store.calls.map { it.first })
    }

    @Test
    fun aKeptVerdictFromAnAttemptWithOtherSettingsIsntShown() = runBlocking<Unit> {
        // The card ended on its first answer (the re-check found the text unclear), made with
        // a-model, though the session's latest attempt ran with another model.
        val kept = SessionRecording(rain, keptContext, 500, newId = { "earlier" })
        kept.attempt(600, emptyList(), "{\"first\":1}", null, null, result.revision, null)
        kept.runWith("ca", settings.copy(model = "another-model"))
        kept.attempt(650, emptyList(), "{\"second\":1}", null, null, null, null, Verdict.Status.Unclear)
        val fixed = result.revision.acceptAll(EditKind.Fix)
        kept.changed(fixed)
        store.kept = kept.close(700, fixed.workingText)
        assertEquals("another-model", store.kept!!.session.model)
        assertEquals(0, store.kept!!.decidedAttempt())

        val other = opening.copy(settings = settings.copy(model = "another-model"))
        assertEquals(Answer("model", null), history.firstAttempt(rain, other, ask = { slowly() }, reuse = { it.raw }))
        history.close(null)
        // With the settings the answer was made with, it's shown.
        val same = history.firstAttempt(rain, opening, ask = { delay(10_000); "model" }, reuse = { it.raw })
        assertEquals("{\"first\":1}", same.value)
    }

    @Test
    fun anotherLanguageWithNoSessionOpenOpensOne() = runBlocking<Unit> {
        // The first check was cancelled before it opened its session.
        history.changeLanguage("es")
        history.languageChanged(rain, opening.copy(requestedLanguage = "es"), ask = { "model" }, reuse = { null })
        assertEquals("es", history.session?.requestedLanguage)
        history.flush()
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun aCardThatStoppedBeingRecordedIsntRecordedAgainWhenItsLanguageChanges() = runBlocking<Unit> {
        history.firstAttempt(rain, opening, ask = { "model" }, reuse = { null })
        history.succeeded(emptyList(), "{}", result)
        history.flush()
        // Turned off from Settings while the card is up: the next change stops the recording.
        environment.enabled = false
        history.changeLanguage("es")
        history.languageChanged(rain, opening.copy(requestedLanguage = "es"), ask = { "model" }, reuse = { null })
        history.succeeded(emptyList(), "{}", result)
        // Turned on again, and another language picked on the same card.
        environment.enabled = true
        history.changeLanguage(null)
        history.languageChanged(rain, opening.copy(requestedLanguage = null), ask = { "model" }, reuse = { null })
        history.succeeded(emptyList(), "{}", result)
        assertNull(history.session)
        closeAfterApplyingFix()
        history.flush()
        assertTrue(store.calls.isEmpty())
        assertTrue(store.registered.isEmpty())
    }

    @Test
    fun aCardOpenedWithHistoryOffStaysUnrecordedWhenItsLanguageChanges() = runBlocking<Unit> {
        environment.enabled = false
        history.firstAttempt(rain, opening, ask = { "model" }, reuse = { null })
        environment.enabled = true
        history.changeLanguage("es")
        history.languageChanged(rain, opening.copy(requestedLanguage = "es"), ask = { "model" }, reuse = { null })
        history.succeeded(emptyList(), "{}", result)
        history.close(null)
        history.flush()
        assertTrue(store.calls.isEmpty())
        assertTrue(store.lookedUp.isEmpty())
        // The next card is recorded once applied.
        history.firstAttempt(rain, opening, ask = { "model" }, reuse = { null })
        history.succeeded(emptyList(), "{}", result)
        closeAfterApplyingFix()
        history.flush()
        assertEquals(listOf("close"), store.calls.map { it.first })
    }

    @Test
    fun theStatusFollowsTheResult() {
        assertEquals(Verdict.Status.Ok, CheckHistory.statusOf(result))
        assertEquals(Verdict.Status.Unclear, CheckHistory.statusOf(CheckResult.Unclear))
        assertEquals(Verdict.Status.WrongLanguage, CheckHistory.statusOf(CheckResult.WrongLanguage("Catalan", "Spanish")))
    }

    private fun closeAfterApplyingFix(recording: CheckHistory = history) {
        val fixed = result.revision.acceptAll(EditKind.Fix)
        recording.changed(fixed)
        recording.close(fixed.workingText)
    }

    private suspend fun slowly(): String {
        delay(100)
        return "model"
    }

    private val keptContext = SessionContext(opening.copy(hostApp = null), "1.0", "device")

    private fun keptSession(id: String, raw: String, language: String? = "ca"): SessionDetail {
        val context = SessionContext(keptContext.opening.copy(requestedLanguage = language), "1.0", "device")
        val recording = SessionRecording(rain, context, 500, newId = { id })
        recording.attempt(600, listOf(Settled("tu", "informal")), raw, null, null, result.revision, null)
        val fixed = result.revision.acceptAll(EditKind.Fix)
        recording.changed(fixed)
        return recording.close(700, fixed.workingText)
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = java.util.ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }

        fun runAll() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    private class FakeStore : HistoryStore {
        val calls: MutableList<Triple<String, SessionRecord, List<SuggestionRecord>>> = java.util.Collections.synchronizedList(mutableListOf())
        val lookedUp: MutableList<Pair<ReuseKey, Long>> = java.util.Collections.synchronizedList(mutableListOf())

        val registered: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
        val deleted: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        override fun registerSession(id: String) { registered += id }
        override fun releaseSession(id: String) { registered -= id }

        @Volatile var failing = false

        @Volatile var kept: SessionDetail? = null

        @Volatile var gate: CompletableDeferred<Unit>? = null

        /** Whether [close] waits for [gate] too. */
        @Volatile var slowClose = false

        override suspend fun save(session: SessionRecord) {
            if (failing) error("disk full")
            calls += Triple("save", session, emptyList())
        }

        override suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>) {
            if (failing) error("disk full")
            if (slowClose) gate?.await()
            calls += Triple("close", session, suggestions)
        }

        override suspend fun reusable(key: ReuseKey, since: Long): SessionDetail? {
            lookedUp += key to since
            gate?.await()
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
        override suspend fun delete(id: String): Boolean {
            deleted += id
            return true
        }
        override suspend fun clear() = Unit
        override suspend fun export(out: OutputStream) = true
    }
}
