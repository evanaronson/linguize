package com.evanaronson.linguize.ui.notes

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.data.write.WriteDraft
import com.evanaronson.linguize.data.write.WriteDraftStore
import com.evanaronson.linguize.testing.KnownBonanzaDefect
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
import org.junit.experimental.categories.Category
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
    private val first = WriteDraft("a", "Current note", "es", "First title", 1)
    private val second = WriteDraft("b", "Other note", "ca", "Second title", 2)

    @After fun clearViewModels() = owners.forEach { it.clear() }

    private fun store() = WriteDraftStore(File(folder.root, WriteDraftStore.FILE_NAME))

    private fun notes(io: CoroutineDispatcher): NotesViewModel {
        store().apply {
            saveCurrent(second)
            saveCurrent(first)
        }
        val owner = ViewModelStore().also { owners += it }
        return ViewModelProvider(owner, viewModelFactory {
            initializer {
                NotesViewModel(store(), { "Sample" }, clock = { 10L }, io = io, newId = { "new" })
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
    @Category(KnownBonanzaDefect::class)
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
        // The new view-model guard silently rejects that input instead of overwriting it later.
        val latestInput = "Typed while the note switch was pending"
        notes.edit(latestInput)

        io.release()
        advanceUntilIdle()

        assertFalse(notes.loading)
        assertEquals(first.copy(text = "Outgoing edit before switching", updatedAt = 10L), store().load().others.single())
        assertEquals("Input from an available editor must not disappear during opening", latestInput, notes.text)
        assertTrue(store().load().drafts.any { it.text == latestInput })
    }

    /** Holds dispatches without blocking a thread; release resumes them on the test scheduler. */
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
