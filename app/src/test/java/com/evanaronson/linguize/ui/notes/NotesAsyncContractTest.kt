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
import org.junit.Assert.assertNotEquals
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

    @Test fun typingBeforeInitialLoadWinsAndKeepsThePreviouslySavedNotes() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher).apply { hold() }
        val notes = notes(io)
        runCurrent()
        assertTrue(io.hasPendingWork)

        notes.edit("Written before restore completed")
        io.release()
        advanceUntilIdle()

        assertEquals("Written before restore completed", notes.text)
        val saved = store().load() // A fresh store reads the file, not the view model's cache.
        assertEquals(notes.text, saved.current?.text)
        assertNotEquals(first.id, saved.currentId)
        assertEquals(first, saved.drafts.single { it.id == first.id })
        assertEquals(second, saved.drafts.single { it.id == second.id })
    }

    @Test fun switchingNotesPersistsTheOutgoingEditAndRestoresTheSelectedLanguageAndTitle() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        val notes = notes(io)
        advanceUntilIdle()
        notes.edit("Unsaved outgoing edit")

        io.hold()
        notes.open(second.id)
        runCurrent()
        assertTrue(io.hasPendingWork)
        assertEquals("Unsaved outgoing edit", notes.text)
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
    fun typingWhileAnotherNoteOpensIsNotReplacedByTheDelayedRead() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        val notes = notes(io)
        advanceUntilIdle()

        io.hold()
        notes.open(second.id)
        runCurrent()
        assertTrue(io.hasPendingWork)
        val latestInput = "Typed while the note switch was pending"
        notes.edit(latestInput)

        io.release()
        advanceUntilIdle()

        // The editor accepted this input. A delayed read must not erase it, and autosave
        // must preserve it on disk. This deliberately fails until note intents are serialized.
        assertEquals(latestInput, notes.text)
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
