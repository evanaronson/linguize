package com.evanaronson.linguize.ui.practice

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.evanaronson.linguize.core.practice.AnswerResult
import com.evanaronson.linguize.data.practice.PracticeStore
import com.evanaronson.linguize.history.patterns.FakePatternHistory
import com.evanaronson.linguize.history.patterns.PatternFixtures.check
import com.evanaronson.linguize.history.patterns.PatternFixtures.fix
import com.evanaronson.linguize.testing.MainDispatcherRule
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

    private fun blockTemp() = File(folder.root, "practice.json.tmp").also {
        assertTrue(it.mkdir())
        File(it, "keep").writeText("storage obstruction")
    }
}
