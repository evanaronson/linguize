package com.evanaronson.linguize.ui.phrasebook

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.evanaronson.linguize.data.phrasebook.PhraseDraft
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import com.evanaronson.linguize.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhrasebookFailureTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()
    private val owners = ViewModelStore()
    private val file get() = File(folder.root, "phrases.json")
    private fun model(store: PhrasebookStore) = PhrasebookViewModel(store, main.dispatcher).also { owners.put("phrases", it) }
    @After fun close() { owners.clear() }

    @Test fun aFailedAddLeavesTheEditorOpenAndRetryUsesItsLatestInput() = runTest(main.dispatcher) {
        val store = PhrasebookStore(file)
        val model = model(store)
        advanceUntilIdle()
        val blocked = blockTemp()
        val saved = mutableListOf<Boolean>()
        model.add(PhraseDraft("old draft")) { saved += it }
        advanceUntilIdle()
        assertEquals(listOf(false), saved)
        assertEquals(PhrasebookFailure.Save, model.failure.value)
        assertEquals(0, model.state.value!!.total)
        assertTrue(PhrasebookStore(file).load().isEmpty())

        assertTrue(blocked.deleteRecursively())
        model.add(PhraseDraft("edited before retry")) { saved += it }
        advanceUntilIdle()
        assertEquals(listOf(false, true), saved)
        assertNull(model.failure.value)
        assertEquals(listOf("edited before retry"), PhrasebookStore(file).load().map { it.phrase })
    }

    @Test fun failedDeleteDoesNotOfferUndoAndFailedUndoKeepsItsRecoveryState() = runTest(main.dispatcher) {
        val store = PhrasebookStore(file)
        val phrase = store.save(PhraseDraft("bon dia"))!!
        val model = model(store)
        advanceUntilIdle()
        var blocked = blockTemp()
        model.delete(phrase.id)
        advanceUntilIdle()
        assertEquals(PhrasebookFailure.Delete, model.failure.value)
        assertNull(model.lastDeleted.value)
        assertEquals(listOf(phrase), store.load())

        assertTrue(blocked.deleteRecursively())
        model.retry()
        advanceUntilIdle()
        assertNull(model.failure.value)
        assertEquals(phrase, model.lastDeleted.value)
        assertTrue(PhrasebookStore(file).load().isEmpty())

        blocked = blockTemp()
        model.undoDelete()
        advanceUntilIdle()
        assertEquals(PhrasebookFailure.Restore, model.failure.value)
        assertEquals(phrase, model.lastDeleted.value)
        model.deleteConfirmed(phrase)
        assertEquals(phrase, model.lastDeleted.value)
        assertTrue(PhrasebookStore(file).load().isEmpty())

        assertTrue(blocked.deleteRecursively())
        model.retry()
        advanceUntilIdle()
        assertNull(model.failure.value)
        assertNull(model.lastDeleted.value)
        assertEquals(listOf(phrase), PhrasebookStore(file).load())
    }

    @Test fun failedInitialLoadRemainsUnknownAndCanBeRetried() = runTest(main.dispatcher) {
        assertTrue(file.mkdir())
        val model = model(PhrasebookStore(file))
        advanceUntilIdle()
        assertNull(model.state.value)
        assertEquals(PhrasebookFailure.Load, model.failure.value)

        assertTrue(file.delete())
        val phrase = PhrasebookStore(file).save(PhraseDraft("bon dia"))!!
        model.retry()
        advanceUntilIdle()
        assertNull(model.failure.value)
        assertEquals(listOf(phrase), model.state.value!!.sections.single().phrases)
    }

    @Test fun failedUndoSurvivesAnotherSuccessfulAddAndDelete() = runTest(main.dispatcher) {
        val store = PhrasebookStore(file)
        val first = store.save(PhraseDraft("bon dia"))!!
        val model = model(store)
        advanceUntilIdle()
        model.delete(first.id)
        advanceUntilIdle()
        val blocked = blockTemp()
        model.undoDelete()
        advanceUntilIdle()
        assertEquals(PhrasebookFailure.Restore, model.failure.value)

        assertTrue(blocked.deleteRecursively())
        model.add(PhraseDraft("fins ara"))
        advanceUntilIdle()
        val second = store.load().single()
        model.delete(second.id)
        advanceUntilIdle()
        assertEquals(second, model.lastDeleted.value)
        assertEquals(PhrasebookFailure.Restore, model.failure.value)

        model.retry()
        advanceUntilIdle()
        assertEquals(listOf(first), PhrasebookStore(file).load())
        assertEquals(second, model.lastDeleted.value)
        assertNull(model.failure.value)
        model.undoDelete()
        advanceUntilIdle()
        assertEquals(setOf(first, second), PhrasebookStore(file).load().toSet())
    }

    private fun blockTemp() = File(folder.root, "phrases.json.tmp").also {
        assertTrue(it.mkdir())
        File(it, "keep").writeText("storage obstruction")
    }
}
