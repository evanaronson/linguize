package com.evanaronson.linguize.ui.practice

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.core.practice.AnswerResult
import com.evanaronson.linguize.core.practice.Grade
import com.evanaronson.linguize.core.practice.Schedule
import com.evanaronson.linguize.data.practice.PracticeStore
import com.evanaronson.linguize.history.patterns.FakePatternHistory
import com.evanaronson.linguize.history.patterns.PatternFixtures.check
import com.evanaronson.linguize.history.patterns.PatternFixtures.fix
import com.evanaronson.linguize.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext

/** Fresh ViewModelStores, real schedule files and saved-state registry restoration. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PracticeRestorationTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()
    private val owners = linkedMapOf<PracticeViewModel, ViewModelStore>()
    private val file get() = File(folder.root, "practice.json")
    private var now = 1_000_000_000_000L
    private val a = check("a", 1, fix("despues", "después"), text = "despues.", language = "es")
    private val b = check("b", 2, fix("estas", "estàs"), text = "estas.", language = "ca")
    private val history = FakePatternHistory(listOf(a, b))
    private fun store(io: CoroutineDispatcher = main.dispatcher) = PracticeStore(file, io)
    private fun model(saved: SavedStateHandle = SavedStateHandle(), io: CoroutineDispatcher = main.dispatcher) =
        PracticeViewModel(history, store(io), clock = { now }, zone = { ZoneOffset.UTC }, savedState = saved)
            .also { owners[it] = ViewModelStore().apply { put("practice", it) } }
    private fun dispose(model: PracticeViewModel) { owners.remove(model)?.clear() }
    @After fun close() { owners.values.forEach { it.clear() } }

    private fun snapshot(saved: SavedStateHandle): SavedStateHandle = SavedStateHandle(
        saved.keys().associateWith { key -> saved.get<String>(key)?.let { String(it.toCharArray()) } },
    )

    @Test fun savedStateRegistryRebuildsTheSameAnsweredRunInAFreshViewModelWithoutCopyingText() = runTest(main.dispatcher) {
        val factory = viewModelFactory {
            initializer {
                PracticeViewModel(history, store(), clock = { now }, zone = { ZoneOffset.UTC }, savedState = createSavedStateHandle())
            }
        }
        val original = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val first = ViewModelProvider(original.get(), factory)[PracticeViewModel::class.java]
        first.enter("ca", "same-page")
        advanceUntilIdle()
        val runId = first.state.value.run!!.id
        val order = first.state.value.correctFirst(first.state.value.run!!.current!!)
        first.submit("estàs")
        advanceUntilIdle()
        val bundle = Bundle()
        original.saveInstanceState(bundle).pause().stop().destroy()
        assertFalse(containsText(bundle, "estas."))
        assertFalse(containsText(bundle, "estàs"))
        val restored = Robolectric.buildActivity(ComponentActivity::class.java).create(bundle).start().resume()
        try {
            val second = ViewModelProvider(restored.get(), factory)[PracticeViewModel::class.java]
            second.enter("ca", "same-page")
            advanceUntilIdle()
            assertNotSame(first, second)
            assertEquals(runId, second.state.value.run!!.id)
            assertEquals("ca", second.state.value.run!!.language)
            assertEquals(AnswerResult.Correct, second.state.value.run!!.answered?.result)
            assertEquals(order, second.state.value.correctFirst(second.state.value.run!!.current!!))
            second.submit("estàs")
            second.retrySave()
            advanceUntilIdle()
            assertEquals(1, store().read().states.getValue("b-s0").reviews)
            second.next()
            assertTrue(second.state.value.run!!.finished)
        } finally {
            restored.pause().stop().destroy()
        }
    }

    @Test fun anOlderUnansweredCheckpointOmitsAnAlreadyReviewedItemWithoutInventingItsScore() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        val first = model(saved)
        first.enter(null, "page")
        advanceUntilIdle()
        val old = snapshot(saved)
        val id = first.state.value.run!!.id
        first.submit("estàs")
        advanceUntilIdle()
        dispose(first)
        val second = model(old)
        second.enter(null, "page")
        advanceUntilIdle()

        assertEquals(id, second.state.value.run!!.id)
        assertEquals(listOf("a-s0"), second.state.value.run!!.queue.map { it.id })
        assertTrue(second.state.value.run!!.grades.isEmpty())
        assertEquals(1, store().read().states.getValue("b-s0").reviews)
        second.submit("después")
        advanceUntilIdle()
        assertEquals(1, store().read().states.getValue("b-s0").reviews)
        assertEquals(1, store().read().states.getValue("a-s0").reviews)
    }

    @Test fun pendingCheckpointCanRecognizeAWriteThatCommittedBeforeItsAcknowledgementWasSaved() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        val io = HeldDispatcher(main.dispatcher)
        val first = model(saved, io)
        first.enter("es", "page")
        advanceUntilIdle()
        io.hold()
        first.reveal()
        runCurrent()
        assertTrue(first.state.value.saving)
        val pending = snapshot(saved)
        io.release()
        advanceUntilIdle()
        dispose(first)
        val second = model(pending)
        second.enter("es", "page")
        advanceUntilIdle()

        assertFalse(second.state.value.saveFailed)
        assertFalse(second.state.value.saving)
        assertTrue(second.state.value.run!!.answered!!.revealed)
        assertEquals(1, store().read().states.getValue("a-s0").reviews)
        second.retrySave()
        second.next()
        assertEquals(listOf("a-s0", "a-s0"), second.state.value.run!!.queue.map { it.id })
        assertEquals(setOf("a-s0"), second.state.value.run!!.repeated)
        second.submit("después")
        second.next()
        advanceUntilIdle()
        assertTrue(second.state.value.run!!.finished)
        assertEquals(1, store().read().states.getValue("a-s0").reviews)
    }

    @Test fun aFailedPendingGradeSurvivesRecreationAndRetriesItsOriginalTimeExactlyOnce() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        val first = model(saved)
        first.enter("es", "page")
        advanceUntilIdle()
        val blocked = blockWrite()
        first.submit("después")
        advanceUntilIdle()
        assertTrue(first.state.value.saveFailed)
        val captured = snapshot(saved)
        dispose(first)
        now += Schedule.DAY
        val second = model(captured)
        second.enter("es", "page")
        advanceUntilIdle()
        assertTrue(second.state.value.saveFailed)
        assertEquals(AnswerResult.Correct, second.state.value.run!!.answered!!.result)
        second.next()
        assertFalse(second.state.value.run!!.finished)
        assertTrue(blocked.deleteRecursively())
        second.retrySave()
        second.retrySave()
        advanceUntilIdle()

        assertFalse(second.state.value.saveFailed)
        val reviewed = store().read().states.getValue("a-s0")
        assertEquals(1, reviewed.reviews)
        assertEquals(now - Schedule.DAY, reviewed.reviewedAt)
        assertEquals(now, reviewed.due)
        second.next()
        assertTrue(second.state.value.run!!.finished)
    }

    @Test fun aRepeatedItemRestoresItsOccurrenceAndNeverGradesTheFirstAnswerTwice() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        val first = model(saved)
        first.enter("es", "page")
        advanceUntilIdle()
        first.reveal()
        advanceUntilIdle()
        first.next()
        val old = first.state.value.run!!
        val captured = snapshot(saved)
        dispose(first)
        val second = model(captured)
        second.enter("es", "page")
        advanceUntilIdle()

        assertEquals(old.id, second.state.value.run!!.id)
        assertEquals(1, second.state.value.run!!.index)
        assertEquals(old.repeated, second.state.value.run!!.repeated)
        assertEquals(old.correctFirst, second.state.value.run!!.correctFirst)
        assertNull(second.state.value.run!!.answered)
        second.reveal()
        second.next()
        advanceUntilIdle()
        assertTrue(second.state.value.run!!.finished)
        assertEquals(2, second.state.value.run!!.steps)
        assertEquals(1, store().read().states.getValue("a-s0").reviews)
    }

    @Test fun aFinishedRestoredPageKeepsItsSummaryButANewPageCanStartDueWork() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        val first = model(saved)
        first.enter("es", "page")
        advanceUntilIdle()
        first.submit("después")
        advanceUntilIdle()
        first.next()
        val runId = first.state.value.run!!.id
        val captured = snapshot(saved)
        dispose(first)
        history.sessions["later"] = check("later", 3, fix("tard", "tarde"), text = "tard.", language = "es")
        val second = model(captured)
        second.enter("es", "page")
        advanceUntilIdle()

        assertEquals(runId, second.state.value.run!!.id)
        assertTrue(second.state.value.run!!.finished)
        assertEquals(1, second.state.value.run!!.right)
        second.enter("es", "page")
        assertEquals(runId, second.state.value.run!!.id)
        second.enter("es", "new-page")
        assertNotEquals(runId, second.state.value.run!!.id)
        assertFalse(second.state.value.run!!.finished)
        assertEquals("later-s0", second.state.value.run!!.current?.id)
    }

    @Test fun anExhaustedStaleCheckpointDoesNotRefillItselfWithTheSupersededReview() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        val first = model(saved)
        first.enter("es", "page")
        advanceUntilIdle()
        val runId = first.state.value.run!!.id
        val unanswered = snapshot(saved)
        first.reveal()
        advanceUntilIdle()
        dispose(first)
        history.sessions["later"] = check("later", 3, fix("tard", "tarde"), text = "tard.", language = "es")
        val second = model(unanswered)
        second.enter("es", "page")
        advanceUntilIdle()

        assertEquals(runId, second.state.value.run!!.id)
        assertTrue(second.state.value.run!!.finished)
        assertTrue(second.state.value.run!!.queue.isEmpty())
        assertEquals(0, second.state.value.run!!.right)
        assertEquals(1, store().read().states.getValue("a-s0").reviews)
        second.enter("es", "new-page")
        assertEquals("later-s0", second.state.value.run!!.current?.id)
        assertNotEquals(runId, second.state.value.run!!.id)
    }

    @Test fun aFailedSourceReadDoesNotConsumeTheSavedRunOrReportAnEmptyDeck() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        val first = model(saved)
        first.enter("ca", "page")
        advanceUntilIdle()
        val runId = first.state.value.run!!.id
        val captured = snapshot(saved)
        dispose(first)
        history.failPage = true
        val second = model(captured)
        second.enter("ca", "page")
        advanceUntilIdle()
        assertTrue(second.state.value.loadFailed)
        assertNull(second.state.value.items)
        assertNull(second.state.value.run)
        history.failPage = false
        second.retryLoad()
        advanceUntilIdle()
        assertFalse(second.state.value.loadFailed)
        assertEquals(runId, second.state.value.run!!.id)
        assertEquals("b-s0", second.state.value.run!!.current?.id)
    }

    @Test fun leavingBeforeLoadRetiresOnlyTheDeferredEntryAndCannotResetProgressScope() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher).apply { hold() }
        val model = model(io = io)
        model.enter("ca", "page")
        runCurrent()
        model.leaveEntry("page")
        model.setLanguage("es")
        io.release()
        advanceUntilIdle()
        assertNull(model.state.value.run)
        assertEquals("es", model.state.value.language)
        model.enter("ca", "page")
        assertEquals("ca", model.state.value.run!!.language)
        model.setLanguage("es")
        model.enter("es", "page")
        assertEquals("ca", model.state.value.run!!.language)
        assertEquals(listOf("b-s0"), model.state.value.run!!.queue.map { it.id })
    }

    @Test fun overlappingLiveRunsStillBothGradeAndTheLaterAcknowledgedRunRestoresItsKnownScore() = runTest(main.dispatcher) {
        val first = model()
        val saved = SavedStateHandle()
        val second = model(saved)
        first.enter("es", "one")
        second.enter("es", "two")
        advanceUntilIdle()
        first.submit("después")
        advanceUntilIdle()
        second.submit("después")
        advanceUntilIdle()
        assertEquals(2, store().read().states.getValue("a-s0").reviews)
        val captured = snapshot(saved)
        val runId = second.state.value.run!!.id
        dispose(first)
        dispose(second)
        val restored = model(captured)
        restored.enter("es", "two")
        advanceUntilIdle()

        assertEquals(runId, restored.state.value.run!!.id)
        assertEquals(AnswerResult.Correct, restored.state.value.run!!.answered?.result)
        assertEquals(1, restored.state.value.run!!.right)
        restored.next()
        assertTrue(restored.state.value.run!!.finished)
        assertEquals(2, store().read().states.getValue("a-s0").reviews)
    }

    @Test fun anotherLiveGradeCanSupersedeARestoredPendingRetryWithoutBeingScoredAsItsAcknowledgement() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        val first = model(saved)
        first.enter("es", "old-page")
        advanceUntilIdle()
        val blocked = blockWrite()
        first.reveal()
        advanceUntilIdle()
        val captured = snapshot(saved)
        dispose(first)
        assertTrue(blocked.deleteRecursively())
        val restored = model(captured)
        restored.enter("es", "old-page")
        advanceUntilIdle()
        assertTrue(restored.state.value.saveFailed)
        val live = model()
        live.enter("es", "other-page")
        advanceUntilIdle()
        live.submit("después")
        advanceUntilIdle()
        restored.retrySave()
        advanceUntilIdle()

        assertFalse(restored.state.value.saveFailed)
        assertTrue(restored.state.value.run!!.finished)
        assertTrue(restored.state.value.run!!.queue.isEmpty())
        assertTrue(restored.state.value.run!!.grades.isEmpty())
        assertEquals(1, store().read().states.getValue("a-s0").reviews)
        assertEquals(Grade.Good, live.state.value.run!!.grades["a-s0"])
    }

    private fun blockWrite() = File(folder.root, "practice.json.tmp").also {
        assertTrue(it.mkdir())
        File(it, "keep").writeText("storage obstruction")
    }

    @Suppress("DEPRECATION")
    private fun containsText(value: Any?, text: String): Boolean = when (value) {
        is Bundle -> value.keySet().any { containsText(value.get(it), text) }
        is Map<*, *> -> value.values.any { containsText(it, text) }
        is Iterable<*> -> value.any { containsText(it, text) }
        is Array<*> -> value.any { containsText(it, text) }
        is String -> text in value
        else -> false
    }

    private class HeldDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        private var held = false
        private val pending = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        fun hold() { held = true }
        fun release() {
            held = false
            while (pending.isNotEmpty()) {
                val (context, block) = pending.removeFirst()
                delegate.dispatch(context, block)
            }
        }
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (held) pending.addLast(context to block) else delegate.dispatch(context, block)
        }
    }
}
