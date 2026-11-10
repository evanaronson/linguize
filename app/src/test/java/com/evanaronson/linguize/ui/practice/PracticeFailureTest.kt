package com.evanaronson.linguize.ui.practice

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.evanaronson.linguize.core.practice.AnswerResult
import com.evanaronson.linguize.core.practice.Deck
import com.evanaronson.linguize.core.practice.Grade
import com.evanaronson.linguize.data.practice.PracticeStore
import com.evanaronson.linguize.history.HistoryEvidenceResult
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.patterns.FakePatternHistory
import com.evanaronson.linguize.history.patterns.PatternFixtures.check
import com.evanaronson.linguize.history.patterns.PatternFixtures.fix
import com.evanaronson.linguize.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PracticeFailureTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()
    private val owners = ViewModelStore()
    private val file get() = File(folder.root, "practice.json")
    private val history = FakePatternHistory(listOf(check("one", 1, fix("despues", "después"), text = "despues.")))
    private val store get() = PracticeStore(file, main.dispatcher)
    private fun model() = PracticeViewModel(history, store, clock = { 1_000_000_000_000L }, zone = { ZoneOffset.UTC }, savedState = SavedStateHandle())
        .also { owners.put("practice", it) }
    @After fun close() { owners.clear() }

    @Test fun failedGradeKeepsFeedbackAndRunUntilExactlyOneSuccessfulRetry() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        assertEquals(1, model.state.value.items!!.size)
        model.start()
        val blocked = blockTemp()
        model.submit("después")
        assertEquals(AnswerResult.Correct, model.state.value.run!!.answered!!.result)
        assertTrue(model.state.value.saving)
        model.next()
        assertFalse(model.state.value.run!!.finished)
        advanceUntilIdle()
        assertTrue(model.state.value.saveFailed)
        assertTrue(model.state.value.progress.states.isEmpty())
        assertTrue(store.read().states.isEmpty())
        assertEquals(1, model.state.value.due)

        // Leaving and reopening must retain the failed answer, not show "nothing due".
        model.close()
        model.start()
        assertNotNull(model.state.value.run?.answered)
        model.next()
        assertFalse(model.state.value.run!!.finished)
        assertTrue(blocked.deleteRecursively())
        model.retrySave()
        model.retrySave() // A second tap while saving cannot duplicate the grade.
        advanceUntilIdle()

        val saved = store.read()
        assertFalse(model.state.value.saveFailed)
        assertFalse(model.state.value.saving)
        assertEquals(saved, model.state.value.progress)
        assertEquals(1, saved.states.values.single().reviews)
        assertEquals(0, model.state.value.due)
        model.next()
        assertTrue(model.state.value.run!!.finished)
        model.close()
        assertNull(model.state.value.run)
    }

    @Test fun aDeletedItemCannotBeRescheduledByRetryingItsFailedGrade() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.start()
        val blocked = blockTemp()
        model.submit("después")
        advanceUntilIdle()
        assertTrue(model.state.value.saveFailed)

        history.delete("one")
        advanceUntilIdle()
        assertTrue(model.state.value.items!!.isEmpty())
        assertTrue(blocked.deleteRecursively())
        model.retrySave()
        advanceUntilIdle()
        assertFalse(model.state.value.saveFailed)
        assertTrue(model.state.value.progress.states.isEmpty())
        assertTrue(store.read().states.isEmpty())
    }

    @Test fun failedProgressLoadLeavesTheFileUntouchedAndRetryCanStartTheDeck() = runTest(main.dispatcher) {
        file.writeText("{not json")
        val model = model()
        advanceUntilIdle()
        assertTrue(model.state.value.loadFailed)
        assertNull(model.state.value.items)
        assertEquals("{not json", file.readText())
        model.start()
        assertNull(model.state.value.run)

        // Simulate restoring a readable backup, then use the screen's retry operation.
        file.writeText("{}")
        model.retryLoad()
        advanceUntilIdle()
        assertFalse(model.state.value.loadFailed)
        assertEquals(1, model.state.value.due)
        model.start()
        assertNotNull(model.state.value.run)
    }


    @Test fun aPartialEvidenceFailureKeepsTheLastDeckAndCannotPruneItsSchedule() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.start()
        model.submit("después")
        advanceUntilIdle()
        val items = model.state.value.items
        val progress = store.read()
        assertEquals(500, model.state.value.window?.limit)
        history.failDetail = "one"
        history.revisions.value++
        advanceUntilIdle()

        assertTrue(model.state.value.loadFailed)
        assertEquals(items, model.state.value.items)
        assertEquals(progress, model.state.value.progress)
        assertEquals(progress, store.read())

        history.failDetail = null
        model.retryLoad()
        advanceUntilIdle()
        assertFalse(model.state.value.loadFailed)
        assertEquals(progress, store.read())
        assertEquals(500, model.state.value.window?.limit)
    }

    @Test fun historyChangingWhilePruneWaitsCannotRemoveANewerSchedule() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        var revision = 0L
        var holdUpdate = false
        val source = object : HistoryStore by history {
            override fun evidenceRevision() = revision
            override suspend fun evidence(limit: Int): HistoryEvidenceResult {
                val read = history.evidence(limit)
                if (holdUpdate) {
                    holdUpdate = false
                    io.hold()
                }
                return when (read) {
                    is HistoryEvidenceResult.Ready -> read.copy(snapshot = read.snapshot.copy(revision = revision))
                    HistoryEvidenceResult.Failed -> read
                }
            }
        }
        store.update { Deck.record(it, "one-s0", Grade.Good, 100, 0) }
        val model = PracticeViewModel(source, PracticeStore(file, io), savedState = SavedStateHandle())
            .also { owners.put("practice", it) }
        advanceUntilIdle()
        val before = model.state.value

        revision++ // Force a fresh read, then pause only its following progress update.
        holdUpdate = true
        model.retryLoad()
        advanceUntilIdle()
        history.sessions.clear()
        history.sessions["two"] = check("two", 2, fix("aqui", "aquí"), text = "aqui.")
        revision++
        val newer = store.update { Deck.record(it, "two-s0", Grade.Good, 200, 0) }
        io.release()
        advanceUntilIdle()

        assertTrue(model.state.value.loadFailed)
        assertEquals(before.items, model.state.value.items)
        assertEquals(before.progress, model.state.value.progress)
        assertEquals(newer, store.read())

        model.retryLoad()
        advanceUntilIdle()
        assertFalse(model.state.value.loadFailed)
        assertEquals(listOf("two-s0"), model.state.value.items!!.map { it.id })
        assertEquals(setOf("two-s0"), store.read().states.keys)
    }

    private fun blockTemp() = File(folder.root, "practice.json.tmp").also {
        assertTrue(it.mkdir())
        File(it, "keep").writeText("storage obstruction")
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
