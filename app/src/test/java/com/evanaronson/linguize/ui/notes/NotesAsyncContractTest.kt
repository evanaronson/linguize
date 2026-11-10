package com.evanaronson.linguize.ui.notes

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.data.write.WriteDraft
import com.evanaronson.linguize.data.write.WriteDraftStore
import com.evanaronson.linguize.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
import kotlin.coroutines.CoroutineContext

/** Real draft files and a held IO dispatch expose input arriving between editor transitions. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotesAsyncContractTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()

    private val owners = mutableListOf<ViewModelStore>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val first = WriteDraft("a", "Current note", "es", "First title", 1)
    private val second = WriteDraft("b", "Other note", "ca", "Second title", 2)

    @After fun clearViewModels() {
        owners.forEach { it.clear() }
        scopes.forEach { it.cancel() }
    }

    private fun store() = WriteDraftStore(File(folder.root, WriteDraftStore.FILE_NAME))

    private fun notes(
        io: CoroutineDispatcher,
        shared: WriteDraftStore = store(),
        seed: Boolean = true,
        commands: CoroutineScope? = null,
    ): NotesViewModel {
        if (seed) shared.apply {
            saveCurrent(second)
            saveCurrent(first)
        }
        val owner = ViewModelStore().also { owners += it }
        return ViewModelProvider(owner, viewModelFactory {
            initializer {
                NotesViewModel(shared, { "Sample" }, clock = { 10L }, io = io, newId = { "new" }, commandScope = commands)
            }
        })[NotesViewModel::class.java]
    }

    @Test fun openingWaitsForInitialListAndKeepsThePreviouslySavedNotes() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher).apply { hold() }
        val notes = notes(io)
        notes.edit("The list has no open editor")
        assertEquals("", notes.text)
        assertNull(notes.openId)
        assertTrue(notes.open(first.id))
        runCurrent()
        assertTrue(io.hasPendingWork)
        assertTrue(notes.loading)

        io.release()
        advanceUntilIdle()

        assertTrue(notes.listed)
        assertFalse(notes.loading)
        assertEquals(first.id, notes.openId)
        assertEquals(first.text, notes.text)
        assertEquals(first.language, notes.language)
        assertEquals(first, store().load().current)
        notes.edit("Written after opening completed")
        advanceUntilIdle()

        val saved = store().load() // A fresh store reads the file, not the view model's cache.
        assertEquals(first.copy(text = "Written after opening completed", updatedAt = 10L), saved.current)
        assertEquals(first.id, saved.currentId)
        assertEquals(second, saved.drafts.single { it.id == second.id })
    }

    @Test fun switchingNotesPersistsTheOutgoingEditAndRestoresTheSelectedLanguageAndTitle() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        val notes = notes(io)
        notes.open(first.id)
        advanceUntilIdle()
        notes.edit("Unsaved outgoing edit")

        io.hold()
        notes.open(second.id)
        runCurrent()
        assertTrue(io.hasPendingWork)
        assertTrue(notes.loading)
        assertEquals(second.id, notes.openId)
        assertEquals("", notes.text)
        assertEquals(first.text, store().load().current?.text)

        io.release()
        advanceUntilIdle()
        assertEquals(second.text, notes.text)
        assertEquals(second.language, notes.language)
        val saved = store().load()
        assertEquals(second, saved.current)
        assertEquals("Unsaved outgoing edit", saved.others.single().text)
        assertEquals(first.title, saved.others.single().title)
        assertEquals(first.language, saved.others.single().language)
    }

    @Test
    fun typingWhileAnotherNoteOpensIsNotSilentlyDiscarded() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        val notes = notes(io)
        notes.open(first.id)
        advanceUntilIdle()
        notes.edit("Outgoing edit before switching")

        io.hold()
        notes.open(second.id)
        runCurrent()
        assertTrue(io.hasPendingWork)
        assertTrue(notes.loading)
        // NotePage still renders NoteEditor while loading, and its text field still calls edit.
        // Those keystrokes belong to the requested note even before its saved contents arrive.
        val latestInput = "Typed while the note switch was pending"
        notes.edit(latestInput)

        io.release()
        advanceUntilIdle()

        assertFalse(notes.loading)
        assertEquals(first.copy(text = "Outgoing edit before switching", updatedAt = 10L), store().load().others.single())
        assertEquals("Input from an available editor must not disappear during opening", latestInput, notes.text)
        assertTrue(store().load().drafts.any { it.text == latestInput })
    }


    @Test fun overlappingOpensKeepEveryNoteAndOnlyTheLatestRequestResolvesNavigation() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        val notes = notes(io)
        notes.open(first.id)
        advanceUntilIdle()
        notes.edit("Outgoing input")
        val resolved = mutableListOf<String?>()

        io.hold()
        notes.open(second.id) { resolved += it }
        runCurrent()
        notes.open("third") { resolved += it }
        runCurrent()
        assertEquals("third", notes.openId)
        assertEquals("", notes.text)

        io.release()
        advanceUntilIdle()

        assertEquals(listOf<String?>(null), resolved)
        assertEquals("third", notes.openId)
        assertEquals("", notes.text)
        assertFalse(notes.loading)
        assertNull(store().load().currentId)
        assertEquals(second, store().load().drafts.single { it.id == second.id })
        assertEquals("Outgoing input", store().load().drafts.single { it.id == first.id }.text)
    }

    @Test fun typingIntoTwoPendingEditorsKeepsEachEditorsOwnTextAndMetadata() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        val notes = notes(io)
        notes.open(first.id)
        advanceUntilIdle()

        io.hold()
        notes.open(second.id)
        notes.edit("Input for B")
        notes.selectLanguage("fr")
        runCurrent()
        notes.open("third")
        notes.edit("Input for C")
        notes.flush()
        runCurrent()
        io.release()
        advanceUntilIdle()

        val saved = store().load()
        assertEquals("third", notes.openId)
        assertEquals("Input for C", notes.text)
        assertEquals("third", saved.currentId)
        assertEquals("Input for C", saved.current?.text)
        assertEquals(second.copy(text = "Input for B", language = "fr", updatedAt = 10L), saved.drafts.single { it.id == second.id })
        assertEquals(first.text, saved.drafts.single { it.id == first.id }.text)
    }

    @Test fun reopeningTheSamePendingEditorKeepsInputAndCompletesItsCallbacks() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher).apply { hold() }
        val notes = notes(io)
        val resolved = mutableListOf<String?>()
        assertTrue(notes.open(second.id) { resolved += it })
        notes.edit("Buffered input")
        assertFalse(notes.open(second.id) { resolved += it })
        runCurrent()
        io.release()
        advanceUntilIdle()

        assertEquals(listOf(second.id, second.id), resolved)
        assertEquals("Buffered input", notes.text)
        assertEquals("Buffered input", store().load().current?.text)
    }

    @Test fun aLateAutosaveCannotReplaceNewerTextOrTheNextSelection() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        val notes = notes(io)
        notes.open(first.id)
        advanceUntilIdle()
        io.hold()
        notes.edit("Older queued text")
        notes.flush()
        runCurrent()
        assertTrue(io.hasPendingWork)
        notes.edit("Newest outgoing text")
        notes.open(second.id)
        runCurrent()
        io.release()
        advanceUntilIdle()

        assertEquals(second.id, notes.openId)
        assertEquals(second.text, notes.text)
        assertEquals(second.id, store().load().currentId)
        assertEquals("Newest outgoing text", store().load().others.single().text)
    }

    @Test fun twoViewModelsShareContentsButAnOlderSaveCannotStealSelection() = runTest(main.dispatcher) {
        val shared = store()
        val io = HeldDispatcher(main.dispatcher)
        val older = notes(io, shared)
        older.open(first.id)
        advanceUntilIdle()
        val newer = notes(main.dispatcher, shared, seed = false)
        newer.open(second.id)
        advanceUntilIdle()
        older.edit("Edited in the earlier host")
        io.hold()
        older.flush()
        runCurrent()
        assertTrue(io.hasPendingWork)
        newer.edit("Edited in the selected host")
        newer.flush()
        runCurrent()
        io.release()
        advanceUntilIdle()

        val saved = store().load()
        assertEquals(second.id, saved.currentId)
        assertEquals("Edited in the selected host", saved.current?.text)
        assertEquals("Edited in the earlier host", saved.others.single().text)
    }

    @Test fun deletingThenUndoingDoesNotLetQueuedSavesReplaceTheRestoredNote() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        val notes = notes(io)
        notes.open(first.id)
        advanceUntilIdle()
        io.hold()
        notes.delete(first.id)
        notes.edit("A save queued after the delete request")
        notes.flush()
        runCurrent()
        io.release()
        advanceUntilIdle()
        assertFalse(store().load().drafts.any { it.id == first.id })
        notes.undoDelete()
        advanceUntilIdle()
        assertEquals(first, store().load().drafts.single { it.id == first.id })

        notes.open(null)
        advanceUntilIdle()
        assertFalse(notes.loading)
        assertEquals(second.id, notes.openId)
        assertEquals(second.text, notes.text)
    }

    @Test fun openingCurrentAfterDeletingAndUndoingItsOnlyNoteResolvesNormally() = runTest(main.dispatcher) {
        val shared = store().apply { saveCurrent(first) }
        val notes = notes(main.dispatcher, shared, seed = false)
        notes.open(first.id)
        advanceUntilIdle()
        notes.delete(first.id)
        advanceUntilIdle()
        notes.undoDelete()
        advanceUntilIdle()
        val resolved = mutableListOf<String?>()
        notes.open(null) { resolved += it }
        advanceUntilIdle()

        assertFalse(notes.loading)
        assertEquals(first.id, notes.openId)
        assertEquals(first.text, notes.text)
        assertEquals(listOf(first.id), resolved)
    }

    @Test fun anAcceptedFlushFinishesAfterTheViewModelIsClearedWithoutChangingAnotherHostsSelection() = runTest(main.dispatcher) {
        val scope = CoroutineScope(SupervisorJob() + main.dispatcher).also { scopes += it }
        val shared = store()
        val io = HeldDispatcher(main.dispatcher)
        val notes = notes(io, shared, commands = scope)
        notes.open(first.id)
        advanceUntilIdle()
        io.hold()
        notes.edit("Accepted before disposal")
        notes.flush()
        owners.first().clear()
        runCurrent()
        val newer = notes(main.dispatcher, shared, seed = false)
        newer.open(second.id)
        runCurrent()
        io.release()
        advanceUntilIdle()

        assertEquals(second.id, store().load().currentId)
        assertEquals("Accepted before disposal", store().load().others.single().text)
    }


    @Test fun aDisposedEditorsHeldSaveCannotRecreateANoteDeletedByAnotherHost() = runTest(main.dispatcher) {
        val scope = CoroutineScope(SupervisorJob() + main.dispatcher).also { scopes += it }
        val shared = store()
        val io = HeldDispatcher(main.dispatcher)
        val older = notes(io, shared, commands = scope)
        older.open(first.id)
        advanceUntilIdle()
        io.hold()
        older.edit("Must not recreate the deleted note")
        older.flush()
        runCurrent()
        assertTrue(io.hasPendingWork)
        owners.first().clear()
        val newer = notes(main.dispatcher, shared, seed = false)
        runCurrent()
        newer.delete(first.id)
        runCurrent()
        assertFalse(store().load().drafts.any { it.id == first.id })
        io.release()
        advanceUntilIdle()

        assertFalse(store().load().drafts.any { it.id == first.id })
        assertEquals(first, newer.deleted)
        newer.undoDelete()
        advanceUntilIdle()
        assertEquals(first, store().load().drafts.single { it.id == first.id })
    }

    @Test fun anOlderHeldSaveCannotOverwriteNewerInputInAnotherHost() = runTest(main.dispatcher) {
        val shared = store()
        val io = HeldDispatcher(main.dispatcher)
        val older = notes(io, shared)
        older.open(first.id)
        advanceUntilIdle()
        io.hold()
        older.edit("Older host's input")
        older.flush()
        runCurrent()
        assertTrue(io.hasPendingWork)
        val newer = notes(main.dispatcher, shared, seed = false)
        newer.open(first.id)
        runCurrent()
        newer.edit("Newer host's input")
        newer.flush()
        runCurrent()
        assertEquals("Newer host's input", store().load().current?.text)
        io.release()
        advanceUntilIdle()

        assertEquals("Newer host's input", newer.text)
        assertEquals("Newer host's input", store().load().current?.text)
    }

    @Test fun aDisposedOpenNeverResolvesNavigationAfterItsReadFinishes() = runTest(main.dispatcher) {
        val scope = CoroutineScope(SupervisorJob() + main.dispatcher).also { scopes += it }
        val io = HeldDispatcher(main.dispatcher).apply { hold() }
        val notes = notes(io, commands = scope)
        var callbacks = 0
        notes.open(first.id) { callbacks++ }
        notes.edit("Accepted while opening")
        notes.flush()
        owners.first().clear()
        runCurrent()
        io.release()
        advanceUntilIdle()

        assertEquals(0, callbacks)
        assertEquals("Accepted while opening", store().load().drafts.single { it.id == first.id }.text)
    }


    @Test fun typingAfterAnotherHostDeletesTheNoteIsANewSaveableEdit() = runTest(main.dispatcher) {
        val shared = store()
        val older = notes(main.dispatcher, shared)
        older.open(first.id)
        advanceUntilIdle()
        val newer = notes(main.dispatcher, shared, seed = false)
        runCurrent()
        newer.delete(first.id)
        runCurrent()
        assertFalse(store().load().drafts.any { it.id == first.id })

        older.edit("Typed after the other host deleted it")
        older.flush()
        advanceUntilIdle()

        assertEquals("Typed after the other host deleted it", store().load().drafts.single { it.id == first.id }.text)
        assertEquals(older.text, store().load().drafts.single { it.id == first.id }.text)
    }

    @Test fun anEmptyAutosaveDoesNotInvalidateAnEditorOpenedWhileItWasPending() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        val notes = notes(io)
        notes.open(first.id)
        advanceUntilIdle()
        notes.edit("")
        io.hold()
        notes.flush()
        runCurrent()
        notes.open(second.id)
        notes.open(first.id)
        runCurrent()
        io.release()
        advanceUntilIdle()
        assertFalse(notes.loading)
        assertEquals(first.id, notes.openId)
        notes.edit("Written again under the same identity")
        notes.flush()
        advanceUntilIdle()

        assertEquals(first.id, store().load().currentId)
        assertEquals("Written again under the same identity", store().load().current?.text)
    }

    @Test fun newerInputWhileBufferedTextIsBeingSavedDoesNotBecomeAnOpenFailure() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        val notes = notes(io)
        notes.open(first.id)
        advanceUntilIdle()
        io.hold()
        val resolved = mutableListOf<String?>()
        notes.open(second.id) { resolved += it }
        notes.edit("First buffered input")
        runCurrent()
        // Wait for B's metadata to be read; its buffered text write is still held on IO.
        repeat(10) {
            if (notes.language != second.language) {
                assertTrue(io.hasPendingWork)
                io.releaseOne()
                runCurrent()
            }
        }
        assertEquals(second.language, notes.language)
        assertTrue(notes.loading)
        assertTrue(io.hasPendingWork)
        notes.edit("Newest buffered input")
        io.release()
        advanceUntilIdle()

        assertNull(notes.storageFailure)
        assertEquals(listOf(second.id), resolved)
        assertEquals("Newest buffered input", notes.text)
        assertEquals("Newest buffered input", store().load().current?.text)
    }

    @Test fun deletingAfterTheCurrentNoteReadCannotGiveItsStaleContentsTheNewDeletionVersion() = runTest(main.dispatcher) {
        val shared = store().apply { saveCurrent(first) }
        val io = HeldDispatcher(main.dispatcher)
        val notes = notes(io, shared, seed = false)
        advanceUntilIdle()
        io.hold()
        notes.open(null)
        runCurrent()
        assertTrue(io.hasPendingWork)
        io.releaseOne { shared.delete(first.id) }
        runCurrent()
        io.release()
        advanceUntilIdle()
        notes.flush()
        advanceUntilIdle()

        assertTrue(store().load().drafts.isEmpty())
        assertNull(store().load().currentId)
        assertFalse(notes.loading)
    }

    @Test fun anAcceptedSwitchAndFlushKeepTheCurrentSelectionAfterDisposal() = runTest(main.dispatcher) {
        val scope = CoroutineScope(SupervisorJob() + main.dispatcher).also { scopes += it }
        val io = HeldDispatcher(main.dispatcher)
        val notes = notes(io, commands = scope)
        notes.open(first.id)
        advanceUntilIdle()
        io.hold()
        notes.open(second.id)
        notes.edit("Saved after the page closed")
        notes.flush()
        owners.first().clear()
        runCurrent()
        io.release()
        advanceUntilIdle()

        assertEquals(second.id, store().load().currentId)
        assertEquals("Saved after the page closed", store().load().current?.text)
    }


    @Test fun reopeningASavedNoteAndFlushingWithoutAnEditIsNotAStaleSaveFailure() = runTest(main.dispatcher) {
        val notes = notes(main.dispatcher)
        notes.open(first.id)
        advanceUntilIdle()
        notes.edit("Previously acknowledged input")
        notes.flush()
        advanceUntilIdle()
        notes.open(second.id)
        advanceUntilIdle()
        notes.open(first.id)
        advanceUntilIdle()
        notes.flush()
        advanceUntilIdle()

        assertNull(notes.storageFailure)
        assertEquals(first.id, store().load().currentId)
        assertEquals("Previously acknowledged input", store().load().current?.text)
    }

    @Test fun anUnchangedEditorCannotOverwriteAnotherHostsPendingNewInput() = runTest(main.dispatcher) {
        val shared = store()
        val older = notes(main.dispatcher, shared)
        older.open(first.id)
        advanceUntilIdle()
        val io = HeldDispatcher(main.dispatcher)
        val newer = notes(io, shared, seed = false)
        newer.open(first.id)
        advanceUntilIdle()
        io.hold()
        newer.edit("New input still waiting on IO")
        newer.flush()
        runCurrent()
        assertTrue(io.hasPendingWork)
        older.flush()
        runCurrent()
        assertNull(older.storageFailure)
        io.release()
        advanceUntilIdle()

        assertEquals("New input still waiting on IO", store().load().current?.text)
        assertNull(newer.storageFailure)
    }

    /** Holds dispatches without blocking a thread; release resumes them on the test scheduler. */
    private class HeldDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        private var held = false
        private val pending = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        val hasPendingWork get() = pending.isNotEmpty()

        fun hold() { held = true }

        fun releaseOne(after: () -> Unit = {}) {
            val (context, block) = pending.removeFirst()
            delegate.dispatch(context) { block.run(); after() }
        }

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
