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

/** Real file failures through the editor: Retry must recover text, deletion and Undo. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotesPersistenceTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()
    private val owners = mutableListOf<ViewModelStore>()
    private val file get() = File(folder.root, WriteDraftStore.FILE_NAME)
    private val original = WriteDraft("a", "First note", "es", updatedAt = 1)

    @After fun clearViewModels() = owners.forEach { it.clear() }

    private fun store() = WriteDraftStore(file)

    private fun notes(io: CoroutineDispatcher = main.dispatcher, shared: WriteDraftStore = store()): NotesViewModel {
        val owner = ViewModelStore().also { owners += it }
        return ViewModelProvider(owner, viewModelFactory {
            initializer {
                NotesViewModel(shared, { "Sample" }, clock = { 10L }, io = io, newId = { "new" })
            }
        })[NotesViewModel::class.java]
    }

    private fun blockWrite(): File = File(folder.root, WriteDraftStore.FILE_NAME + ".tmp").also {
        assertTrue(it.mkdir())
        File(it, "keep").writeText("blocks the write")
    }

    @Test fun failedAutosaveKeepsTheEditorAndRetriesItsLatestText() = runTest(main.dispatcher) {
        store().saveCurrent(original)
        val notes = notes()
        notes.open(original.id)
        advanceUntilIdle()
        val obstruction = blockWrite()

        notes.edit("Unsaved edit")
        advanceUntilIdle()
        assertEquals(NotesStorageFailure.Save, notes.storageFailure)
        assertEquals("Unsaved edit", notes.text)
        assertEquals(original, notes.notes.single())
        assertEquals(original, store().load().current)

        notes.edit("Latest edit before Retry")
        assertTrue(obstruction.deleteRecursively())
        notes.retryStorage()
        advanceUntilIdle()

        assertNull(notes.storageFailure)
        assertEquals("Latest edit before Retry", notes.text)
        assertEquals(notes.text, store().load().current?.text)
        assertEquals(store().load().drafts, notes.notes)
    }

    @Test fun aFailedDeleteKeepsTheNoteAndOffersUndoOnlyAfterRetrySucceeds() = runTest(main.dispatcher) {
        store().saveCurrent(original)
        val notes = notes()
        notes.open(original.id)
        advanceUntilIdle()
        val obstruction = blockWrite()

        notes.delete(original.id)
        advanceUntilIdle()
        assertEquals(NotesStorageFailure.Delete, notes.storageFailure)
        assertEquals(listOf(original), notes.notes)
        assertEquals(original.id, notes.openId)
        assertEquals(original.text, notes.text)
        assertNull(notes.deleted)
        assertEquals(original, store().load().current)

        assertTrue(obstruction.deleteRecursively())
        notes.retryStorage()
        advanceUntilIdle()
        assertNull(notes.storageFailure)
        assertTrue(notes.notes.isEmpty())
        assertEquals(original, notes.deleted)
        assertNull(notes.openId)
        assertTrue(store().load().drafts.isEmpty())
    }

    @Test fun aFailedUndoKeepsTheDeletedNoteAvailableForRetry() = runTest(main.dispatcher) {
        store().saveCurrent(original)
        val notes = notes()
        advanceUntilIdle()
        notes.delete(original.id)
        advanceUntilIdle()
        assertEquals(original, notes.deleted)
        val obstruction = blockWrite()

        notes.undoDelete()
        advanceUntilIdle()
        assertEquals(NotesStorageFailure.Restore, notes.storageFailure)
        assertEquals(original, notes.deleted)
        assertTrue(notes.notes.isEmpty())
        assertTrue(store().load().drafts.isEmpty())

        assertTrue(obstruction.deleteRecursively())
        notes.retryStorage()
        advanceUntilIdle()
        assertNull(notes.storageFailure)
        assertNull(notes.deleted)
        assertEquals(listOf(original), notes.notes)
        assertEquals(notes.notes, store().load().drafts)
    }

    @Test fun retryingAFailedUndoRestoresItsDraftAndKeepsALaterUndoAvailable() = runTest(main.dispatcher) {
        val other = WriteDraft("b", "Other note", updatedAt = 2)
        store().saveCurrent(other)
        store().saveCurrent(original)
        val notes = notes()
        advanceUntilIdle()
        notes.delete(original.id)
        advanceUntilIdle()
        val obstruction = blockWrite()
        notes.undoDelete()
        advanceUntilIdle()
        assertEquals(NotesStorageFailure.Restore, notes.storageFailure)
        assertTrue(obstruction.deleteRecursively())

        notes.delete(other.id)
        advanceUntilIdle()
        assertEquals(other, notes.deleted)
        notes.retryStorage()
        advanceUntilIdle()

        assertNull(notes.storageFailure)
        assertEquals(listOf(original), store().load().drafts)
        assertEquals(other, notes.deleted)
        notes.undoDelete()
        advanceUntilIdle()
        assertEquals(setOf(original, other), store().load().drafts.toSet())
    }

    @Test fun twoFailedUndosRetainBothDraftsUntilEachRetrySucceeds() = runTest(main.dispatcher) {
        val other = WriteDraft("b", "Other note", updatedAt = 2)
        store().saveCurrent(other)
        store().saveCurrent(original)
        val notes = notes()
        advanceUntilIdle()
        notes.delete(original.id)
        advanceUntilIdle()
        var obstruction = blockWrite()
        notes.undoDelete()
        advanceUntilIdle()
        assertTrue(obstruction.deleteRecursively())
        notes.delete(other.id)
        advanceUntilIdle()
        obstruction = blockWrite()
        notes.undoDelete()
        advanceUntilIdle()
        assertEquals(NotesStorageFailure.Restore, notes.storageFailure)
        assertTrue(store().load().drafts.isEmpty())

        assertTrue(obstruction.deleteRecursively())
        notes.retryStorage()
        advanceUntilIdle()
        assertEquals(listOf(other), store().load().drafts)
        assertEquals(NotesStorageFailure.Restore, notes.storageFailure)
        notes.retryStorage()
        advanceUntilIdle()
        assertNull(notes.storageFailure)
        assertEquals(setOf(original, other), store().load().drafts.toSet())
    }

    @Test fun aFailedSwitchRestoresOutgoingInputAndRetriesWithAnyNewEdits() = runTest(main.dispatcher) {
        val other = WriteDraft("b", "Other note", "ca", updatedAt = 2)
        store().saveCurrent(other)
        store().saveCurrent(original)
        val notes = notes()
        notes.open(original.id)
        advanceUntilIdle()
        notes.edit("Outgoing unsaved edit")
        val obstruction = blockWrite()

        notes.open(other.id)
        advanceUntilIdle()
        assertEquals(NotesStorageFailure.Open, notes.storageFailure)
        assertFalse(notes.loading)
        assertEquals(original.id, notes.openId)
        assertEquals("Outgoing unsaved edit", notes.text)
        assertEquals(original, store().load().current)

        notes.edit("Outgoing latest edit")
        assertTrue(obstruction.deleteRecursively())
        notes.retryStorage()
        advanceUntilIdle()
        assertNull(notes.storageFailure)
        assertEquals(other.id, notes.openId)
        assertEquals(other.text, notes.text)
        assertEquals(other, store().load().current)
        assertEquals("Outgoing latest edit", store().load().others.single().text)
    }

    @Test fun aReadFailureIsNotShownAsAnEmptyListAndRetryLoadsTheNotes() = runTest(main.dispatcher) {
        assertTrue(file.mkdir())
        File(file, "keep").writeText("existing content")
        val notes = notes()
        advanceUntilIdle()

        assertEquals(NotesStorageFailure.Read, notes.storageFailure)
        assertFalse(notes.listed)
        assertEquals("existing content", File(file, "keep").readText())
        assertTrue(file.deleteRecursively())
        store().saveCurrent(original)

        notes.retryStorage()
        advanceUntilIdle()
        assertNull(notes.storageFailure)
        assertTrue(notes.listed)
        assertEquals(listOf(original), notes.notes)
        assertEquals(original, store().load().current)
    }

    @Test fun emptyingANoteDoesNotAcknowledgeItsDeletionBeforeTheWriteSucceeds() = runTest(main.dispatcher) {
        store().saveCurrent(original)
        val notes = notes()
        notes.open(original.id)
        advanceUntilIdle()
        val obstruction = blockWrite()

        notes.edit("")
        advanceUntilIdle()
        assertEquals(NotesStorageFailure.Save, notes.storageFailure)
        assertEquals("", notes.text)
        assertEquals(original, notes.notes.single())
        assertEquals(original, store().load().current)

        assertTrue(obstruction.deleteRecursively())
        notes.retryStorage()
        advanceUntilIdle()
        assertNull(notes.storageFailure)
        assertTrue(notes.notes.isEmpty())
        assertTrue(store().load().drafts.isEmpty())
        assertEquals(original.id, notes.openId)
    }

    @Test fun openingAnotherNoteWhileDeleteIsPendingCannotResurrectTheDeletedNote() = runTest(main.dispatcher) {
        val other = WriteDraft("b", "Other note", updatedAt = 2)
        store().saveCurrent(other)
        store().saveCurrent(original)
        val io = HeldDispatcher(main.dispatcher)
        val notes = notes(io)
        notes.open(original.id)
        advanceUntilIdle()

        io.hold()
        notes.delete(original.id)
        runCurrent()
        assertTrue(io.hasPendingWork)
        assertNull(notes.deleted)
        assertEquals(original, store().load().current)
        notes.open(other.id)
        runCurrent()
        assertTrue(notes.loading)

        io.release()
        advanceUntilIdle()
        assertNull(notes.storageFailure)
        assertEquals(other.id, notes.openId)
        assertEquals(listOf(other), notes.notes)
        assertEquals(listOf(other), store().load().drafts)
    }

    @Test fun aPendingDeleteThatFailsDoesNotDiscardOutgoingEditsOnTheNextOpen() = runTest(main.dispatcher) {
        val other = WriteDraft("b", "Other note", updatedAt = 2)
        store().saveCurrent(other)
        store().saveCurrent(original)
        val io = HeldDispatcher(main.dispatcher)
        val notes = notes(io)
        notes.open(original.id)
        advanceUntilIdle()
        notes.edit("Keep this unsaved text")
        val obstruction = blockWrite()
        io.hold()
        notes.delete(original.id)
        runCurrent()
        assertTrue(io.hasPendingWork)
        notes.open(other.id)
        runCurrent()

        io.release()
        advanceUntilIdle()
        assertEquals(NotesStorageFailure.Open, notes.storageFailure)
        assertEquals(original.id, notes.openId)
        assertEquals("Keep this unsaved text", notes.text)
        assertNull(notes.deleted)
        assertEquals(original, store().load().current)

        assertTrue(obstruction.deleteRecursively())
        notes.retryStorage()
        advanceUntilIdle()
        assertNull(notes.storageFailure)
        assertEquals(other.id, notes.openId)
        assertEquals("Keep this unsaved text", store().load().others.single().text)
    }


    @Test fun aFailedSwitchRetainsBothOutgoingAndTargetInputThroughRetry() = runTest(main.dispatcher) {
        val other = WriteDraft("b", "Other note", "ca", "Other title", 2)
        store().saveCurrent(other)
        store().saveCurrent(original)
        val io = HeldDispatcher(main.dispatcher)
        val notes = notes(io)
        notes.open(original.id)
        advanceUntilIdle()
        notes.edit("Unsaved outgoing input")
        val obstruction = blockWrite()
        io.hold()
        val resolved = mutableListOf<String?>()
        notes.open(other.id) { resolved += it }
        notes.edit("Input for the requested note")
        runCurrent()
        io.release()
        advanceUntilIdle()

        assertEquals(other.id, notes.openId)
        assertEquals("Input for the requested note", notes.text)
        assertEquals(original, store().load().current)
        assertTrue(obstruction.deleteRecursively())
        notes.retryStorage()
        advanceUntilIdle()

        assertNull(notes.storageFailure)
        assertEquals(other.id, notes.openId)
        assertEquals("Input for the requested note", notes.text)
        assertEquals(other.copy(text = notes.text, updatedAt = 10L), store().load().current)
        assertEquals("Unsaved outgoing input", store().load().others.single().text)
        assertEquals(listOf(other.id), resolved)
    }

    @Test fun aStaleFailedUndoCannotReplaceNewTextWrittenUnderTheSameNoteId() = runTest(main.dispatcher) {
        store().saveCurrent(original)
        val notes = notes()
        advanceUntilIdle()
        notes.delete(original.id)
        advanceUntilIdle()
        val obstruction = blockWrite()
        notes.undoDelete()
        advanceUntilIdle()
        assertEquals(NotesStorageFailure.Restore, notes.storageFailure)
        assertTrue(obstruction.deleteRecursively())
        notes.open(original.id)
        advanceUntilIdle()
        notes.edit("New text under the restored identity")
        notes.flush()
        advanceUntilIdle()

        notes.retryStorage()
        advanceUntilIdle()

        assertNull(notes.storageFailure)
        assertEquals("New text under the restored identity", store().load().current?.text)
        assertEquals("New text under the restored identity", notes.text)
    }


    @Test fun aFailedUndoCannotReverseALaterDeletionFromAnotherHost() = runTest(main.dispatcher) {
        val shared = store().apply { saveCurrent(original) }
        val older = notes(shared = shared)
        advanceUntilIdle()
        older.delete(original.id)
        advanceUntilIdle()
        val obstruction = blockWrite()
        older.undoDelete()
        advanceUntilIdle()
        assertEquals(NotesStorageFailure.Restore, older.storageFailure)
        assertTrue(obstruction.deleteRecursively())
        shared.restore(original)
        val newer = notes(shared = shared)
        advanceUntilIdle()
        newer.delete(original.id)
        advanceUntilIdle()

        older.retryStorage()
        advanceUntilIdle()

        assertTrue(store().load().drafts.isEmpty())
        assertNull(older.deleted)
        assertNull(older.storageFailure)
        assertEquals(original, newer.deleted)
    }

    private class HeldDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        private var held = false
        private val pending = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        val hasPendingWork get() = pending.isNotEmpty()

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
