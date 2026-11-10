package com.evanaronson.linguize.ui.memory

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.core.memory.FactCategory
import com.evanaronson.linguize.core.memory.MemoryChanges
import com.evanaronson.linguize.core.memory.NewFact
import com.evanaronson.linguize.data.memory.MemoryStore
import com.evanaronson.linguize.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A failed command must remain visible and retryable without closing an editor or losing Undo. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MemoryPersistenceViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()
    private val owners = mutableListOf<ViewModelStore>()
    private val file get() = File(folder.root, MemoryStore.FILE_NAME)

    @After fun close() = owners.forEach { it.clear() }

    private fun model(store: MemoryStore = MemoryStore(file)): MemoryViewModel {
        val owner = ViewModelStore().also { owners += it }
        return ViewModelProvider(owner, viewModelFactory {
            initializer { MemoryViewModel(store, io = main.dispatcher) }
        })[MemoryViewModel::class.java]
    }

    @Test fun failedLoadShowsNoInventedStateAndRetryReadsTheExistingOptOut() = runTest(main.dispatcher) {
        assertTrue(file.mkdir())
        val model = model()
        advanceUntilIdle()
        assertEquals(MemoryFailure.Load, model.failure.value)
        assertNull(model.state.value)

        assertTrue(file.delete())
        MemoryStore(file).apply {
            add("Prefers casual Spanish", FactCategory.Preferences)
            setEnabled(false)
        }
        model.retry()
        advanceUntilIdle()

        assertNull(model.failure.value)
        assertFalse(model.state.value!!.enabled)
        assertEquals(1, model.state.value!!.total)
    }

    @Test fun failedEditorSaveMustBeResubmittedWithTheLatestText() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        val callbacks = mutableListOf<Boolean>()
        val blocked = blockWrites()
        model.add("Lives in Barcelona", FactCategory.About) { callbacks += it }
        advanceUntilIdle()

        assertEquals(listOf(false), callbacks)
        assertEquals(MemoryFailure.Editor, model.failure.value)
        assertEquals(0, model.state.value!!.total)
        assertTrue(MemoryStore(file).load().facts.isEmpty())

        assertTrue(blocked.deleteRecursively())
        model.retry()
        advanceUntilIdle()
        assertEquals(listOf(false), callbacks)
        assertTrue(MemoryStore(file).load().facts.isEmpty())
        model.add("Lives in Girona", FactCategory.About) { callbacks += it }
        advanceUntilIdle()
        assertEquals(listOf(false, true), callbacks)
        assertNull(model.failure.value)
        assertEquals(1, model.state.value!!.total)
        assertEquals("Lives in Girona", MemoryStore(file).load().facts.single().text)
    }

    @Test fun failedDeleteAndUndoKeepTheLastAcknowledgedStateUntilRetry() = runTest(main.dispatcher) {
        val store = MemoryStore(file)
        val fact = store.add("Lives in Barcelona", FactCategory.About)!!
        val model = model(store)
        advanceUntilIdle()
        val blockedDelete = blockWrites()
        model.delete(fact.id)
        advanceUntilIdle()

        assertEquals(MemoryFailure.Save, model.failure.value)
        assertNull(model.lastDeleted.value)
        assertEquals(1, model.state.value!!.total)
        assertEquals(listOf(fact), MemoryStore(file).load().facts)

        assertTrue(blockedDelete.deleteRecursively())
        model.retry()
        advanceUntilIdle()
        assertEquals(fact, model.lastDeleted.value)
        assertEquals(0, model.state.value!!.total)
        val blockedUndo = blockWrites()
        model.undoDelete()
        advanceUntilIdle()

        assertTrue(model.undoFailed.value)
        assertEquals(fact, model.lastDeleted.value)
        assertTrue(MemoryStore(file).load().facts.isEmpty())
        assertTrue(blockedUndo.deleteRecursively())
        model.retryUndo()
        advanceUntilIdle()

        assertNull(model.failure.value)
        assertNull(model.lastDeleted.value)
        assertFalse(model.undoFailed.value)
        assertEquals(listOf(fact), MemoryStore(file).load().facts)
    }

    @Test fun failedUndoSurvivesALaterDeletionAndClearCancelsItsRetry() = runTest(main.dispatcher) {
        val store = MemoryStore(file)
        val a = store.add("A", FactCategory.About)!!
        val b = store.add("B", FactCategory.About)!!
        val model = model(store)
        advanceUntilIdle()
        model.delete(a.id)
        advanceUntilIdle()
        val blocked = blockWrites()
        model.undoDelete()
        advanceUntilIdle()
        assertTrue(model.undoFailed.value)
        assertTrue(blocked.deleteRecursively())

        model.delete(b.id)
        advanceUntilIdle()
        assertEquals(b, model.lastDeleted.value)
        assertTrue(model.undoFailed.value)
        model.retryUndo()
        advanceUntilIdle()
        assertEquals(listOf(a), MemoryStore(file).load().facts)
        assertFalse(model.undoFailed.value)
        assertEquals(b, model.lastDeleted.value)

        val blockedAgain = blockWrites()
        model.undoDelete()
        advanceUntilIdle()
        assertTrue(model.undoFailed.value)
        assertTrue(blockedAgain.deleteRecursively())
        model.forgetEverything()
        model.retryUndo() // Queued before Clear completes: it must not restore old facts afterwards.
        advanceUntilIdle()
        assertTrue(MemoryStore(file).load().facts.isEmpty())
        assertFalse(model.undoFailed.value)
    }

    @Test fun failedClearKeepsUndoAndDoesNotCloseTheEditorUntilSaved() = runTest(main.dispatcher) {
        val store = MemoryStore(file)
        val removed = store.add("A", FactCategory.About)!!
        val kept = store.add("B", FactCategory.About)!!
        val model = model(store)
        advanceUntilIdle()
        model.delete(removed.id)
        advanceUntilIdle()
        var completed = false
        val blocked = blockWrites()

        model.forgetEverything { completed = true }
        advanceUntilIdle()
        assertFalse(completed)
        assertEquals(removed, model.lastDeleted.value)
        assertEquals(listOf(kept), MemoryStore(file).load().facts)
        assertEquals(MemoryFailure.Save, model.failure.value)

        assertTrue(blocked.deleteRecursively())
        model.retry()
        advanceUntilIdle()
        assertTrue(completed)
        assertNull(model.lastDeleted.value)
        assertTrue(MemoryStore(file).load().facts.isEmpty())
    }

    @Test fun failedUndoCannotRestoreALearnedClaimAfterItsSourceIsDeleted() = runTest(main.dispatcher) {
        val store = MemoryStore(file)
        val fact = store.update { state, now, newId ->
            state.applying(MemoryChanges(add = listOf(NewFact("Lives in Paris", FactCategory.About))), "chat:deleted", now, newId)
        }.facts.single()
        val model = model(store)
        advanceUntilIdle()
        model.delete(fact.id)
        advanceUntilIdle()
        val blocked = blockWrites()
        model.undoDelete()
        advanceUntilIdle()
        assertTrue(model.undoFailed.value)

        assertTrue(blocked.deleteRecursively())
        store.forgetSource("chat:deleted")
        model.retryUndo()
        advanceUntilIdle()

        assertFalse(model.undoFailed.value)
        assertNull(model.lastDeleted.value)
        assertTrue(MemoryStore(file).load().facts.isEmpty())
        assertFalse("chat:deleted" in store.load().sources)
    }

    private fun blockWrites(): File = File(folder.root, MemoryStore.FILE_NAME + ".tmp").also {
        assertTrue(it.mkdir())
        File(it, "blocker").writeText("keep")
    }
}
