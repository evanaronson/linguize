package com.evanaronson.linguize.ui.home

import android.app.Application
import android.content.Context
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.ChatPartner
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.core.memory.UserMemory
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.data.chat.ChatPreferences
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.data.write.WriteDraft
import com.evanaronson.linguize.data.write.WriteDraftStore
import com.evanaronson.linguize.llm.chat.ChatPrompt
import com.evanaronson.linguize.testing.MainDispatcherRule
import com.evanaronson.linguize.ui.chat.ChatViewModel
import com.evanaronson.linguize.ui.notes.NotesViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.coroutines.CoroutineContext

/** Restored routes use fresh owners and real stores; held reads outlive the page requesting them. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeRouteCallbackLifecycleTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val io = HeldDispatcher(main.dispatcher)
    private val chats = ChatStore(context, name = null, dispatcher = io)
    private val scope = CoroutineScope(SupervisorJob() + main.dispatcher)
    private val owners = mutableListOf<ViewModelStore>()

    @After fun close() {
        io.release()
        owners.forEach(ViewModelStore::clear)
        main.dispatcher.scheduler.runCurrent()
        scope.cancel()
        chats.close()
    }

    private fun freshChat(): ChatViewModel {
        val owner = ViewModelStore().also(owners::add)
        val partner = ChatPartner(
            FakePreferences(), ProviderKeys { null }, emptyMap(), { UserMemory.None }, dispatcher = main.dispatcher,
        ) { ChatPrompt("chat", "{}") }
        return ViewModelProvider(owner, viewModelFactory {
            initializer {
                ChatViewModel(
                    store = chats, partner = partner, preferences = ChatPreferences(context), memory = { UserMemory.None },
                    startLanguage = { "es" }, drainDeletions = {}, people = { emptyList() },
                    translate = { error("These navigation tests do not translate") }, warmTargets = {},
                    appScope = scope, clock = { 10L },
                )
            }
        })[ChatViewModel::class.java]
    }

    private fun notesStore() = WriteDraftStore(File(folder.root, WriteDraftStore.FILE_NAME))

    private fun freshNotes(): NotesViewModel {
        val owner = ViewModelStore().also(owners::add)
        return ViewModelProvider(owner, viewModelFactory {
            initializer {
                NotesViewModel(notesStore(), { "Untouched sample" }, io = io, newId = { "new-note" }, commandScope = scope)
            }
        })[NotesViewModel::class.java]
    }

    private suspend fun seedChat() {
        assertTrue(chats.create(Conversation("stored-chat", "A saved conversation", "es", 1, 2)))
        assertTrue(chats.add(ChatMessage.partner("opener", "stored-chat", "Hola", 2)))
    }

    @Test fun aFreshOwnerReadsTheExplicitConversationFromTheRestoredRoute() = runTest(main.dispatcher) {
        seedChat()
        val navigation = Navigation.restore(Navigation(Screen.Chat).open(Screen.ChatThread("stored-chat")).saved)
        val chat = freshChat()
        assertNull(chat.thread)
        chat.open((navigation.current as Screen.ChatThread).id!!)
        runCurrent()
        assertEquals("stored-chat", chat.thread!!.conversation.id)
        assertEquals(listOf("Hola"), chat.thread!!.messages.map { it.text })
    }

    @Test fun aMissingRestoredConversationCannotPopSettingsOpenedDuringItsRead() = runTest(main.dispatcher) {
        var navigation = Navigation.restore(Navigation(Screen.Chat).open(Screen.ChatThread("deleted")).saved)
        val entry = navigation
        val chat = freshChat()
        var missingCallbacks = 0
        io.hold = true
        chat.open("deleted") {
            missingCallbacks++
            if (navigation.owns(entry)) navigation = navigation.back()
        }
        runCurrent()
        assertTrue(io.hasPendingWork)
        navigation = navigation.open(Screen.Settings)
        io.release()
        runCurrent()
        assertEquals(1, missingCallbacks)
        assertEquals(Screen.Settings, navigation.current)
        assertEquals(Screen.ChatThread("deleted"), navigation.parent)
    }

    @Test fun aMissingRestoredConversationCannotPopANewerTab() = runTest(main.dispatcher) {
        var navigation = Navigation.restore(Navigation(Screen.Chat).open(Screen.ChatThread("deleted")).saved)
        val entry = navigation
        val chat = freshChat()
        io.hold = true
        var missing = false
        chat.open("deleted") {
            missing = true
            if (navigation.owns(entry)) navigation = navigation.back()
        }
        runCurrent()
        navigation = navigation.open(Screen.Progress)
        io.release()
        runCurrent()
        assertTrue(missing)
        assertEquals(Screen.Progress, navigation.current)
    }

    @Test fun aMissingCallbackFromThePreviousVisitCannotPopTheSameRouteOnReturn() = runTest(main.dispatcher) {
        var navigation = Navigation.restore(Navigation(Screen.Chat).open(Screen.ChatThread("deleted")).saved)
        val oldEntry = navigation
        val chat = freshChat()
        var missingCallbacks = 0
        io.hold = true
        chat.open("deleted") {
            missingCallbacks++
            if (navigation.owns(oldEntry)) navigation = navigation.back()
        }
        runCurrent()
        navigation = navigation.open(Screen.Settings).back()
        assertEquals(oldEntry, navigation)
        assertFalse(navigation.owns(oldEntry))
        // The old read can finish before the returning page starts its new LaunchedEffect.
        io.release()
        runCurrent()
        assertEquals(1, missingCallbacks)
        assertEquals(Screen.ChatThread("deleted"), navigation.current)

        val currentEntry = navigation
        chat.open("deleted") { if (navigation.owns(currentEntry)) navigation = navigation.back() }
        runCurrent()
        assertEquals(Screen.Chat, navigation.current)
    }

    @Test fun delayedCurrentConversationResolutionCannotReplaceANewerPage() = runTest(main.dispatcher) {
        seedChat()
        var navigation = Navigation.restore(Navigation(Screen.Chat).open(Screen.ChatThread(null)).saved)
        val entry = navigation
        val chat = freshChat()
        var resolved: String? = null
        io.hold = true
        chat.openCurrent(null) { id ->
            resolved = id
            if (navigation.owns(entry)) navigation = if (id == null) navigation.back() else navigation.replace(Screen.ChatThread(id))
        }
        runCurrent()
        navigation = navigation.open(Screen.Settings)
        io.release()
        runCurrent()
        assertEquals("stored-chat", resolved)
        assertEquals(Screen.Settings, navigation.current)
        assertEquals(Screen.ChatThread(null), navigation.parent)
    }

    @Test fun currentConversationResolutionBelongsToTheReturningVisit() = runTest(main.dispatcher) {
        seedChat()
        var navigation = Navigation.restore(Navigation(Screen.Chat).open(Screen.ChatThread(null)).saved)
        val oldEntry = navigation
        val chat = freshChat()
        var oldResolved = false
        io.hold = true
        chat.openCurrent(null) { id ->
            oldResolved = true
            if (navigation.owns(oldEntry)) navigation = if (id == null) navigation.back() else navigation.replace(Screen.ChatThread(id))
        }
        runCurrent()
        navigation = navigation.open(Screen.Settings).back()
        io.release()
        runCurrent()
        assertTrue(oldResolved)
        assertEquals(Screen.ChatThread(null), navigation.current)

        val currentEntry = navigation
        chat.openCurrent(null) { id ->
            if (navigation.owns(currentEntry)) navigation = if (id == null) navigation.back() else navigation.replace(Screen.ChatThread(id))
        }
        runCurrent()
        assertEquals(Screen.ChatThread("stored-chat"), navigation.current)
    }

    @Test fun aFreshNotesOwnerLoadsTheExplicitIdInsteadOfTheStoresCurrentNote() = runTest(main.dispatcher) {
        notesStore().apply {
            saveCurrent(WriteDraft("restored", "The restored note", "ca", updatedAt = 1))
            saveCurrent(WriteDraft("current", "Another host's current note", "es", updatedAt = 2))
        }
        val navigation = Navigation.restore(Navigation(Screen.Notes).open(Screen.Note("restored")).saved)
        val notes = freshNotes()
        assertNull(notes.openId)
        notes.open((navigation.current as Screen.Note).id)
        runCurrent()
        assertEquals("restored", notes.openId)
        assertEquals("The restored note", notes.text)
        assertEquals("ca", notes.language)
    }

    @Test fun delayedCurrentNoteResolutionCannotReplaceANewerPage() = runTest(main.dispatcher) {
        notesStore().saveCurrent(WriteDraft("stored-note", "Kept text", updatedAt = 1))
        var navigation = Navigation.restore(Navigation(Screen.Notes).open(Screen.Note(null)).saved)
        val entry = navigation
        io.hold = true
        val notes = freshNotes()
        var resolved: String? = null
        notes.open(null) { id ->
            resolved = id
            if (navigation.owns(entry) && id != null) navigation = navigation.resolveNote(id)
        }
        runCurrent()
        assertTrue(io.hasPendingWork)
        navigation = navigation.open(Screen.Settings)
        io.release()
        runCurrent()
        assertEquals("stored-note", resolved)
        assertEquals(Screen.Settings, navigation.current)
        assertEquals(Screen.Note(null), navigation.parent)
    }

    @Test fun currentNoteResolutionBelongsToTheReturningVisit() = runTest(main.dispatcher) {
        notesStore().saveCurrent(WriteDraft("stored-note", "Kept text", updatedAt = 1))
        var navigation = Navigation.restore(Navigation(Screen.Notes).open(Screen.Note(null)).saved)
        val oldEntry = navigation
        io.hold = true
        val notes = freshNotes()
        var oldResolved = false
        notes.open(null) { id ->
            oldResolved = true
            if (navigation.owns(oldEntry) && id != null) navigation = navigation.resolveNote(id)
        }
        runCurrent()
        navigation = navigation.open(Screen.Settings).back()
        assertEquals(oldEntry, navigation)
        assertFalse(navigation.owns(oldEntry))
        io.release()
        runCurrent()
        assertTrue(oldResolved)
        assertEquals(Screen.Note(null), navigation.current)

        val currentEntry = navigation
        notes.open(null) { id ->
            if (navigation.owns(currentEntry) && id != null) navigation = navigation.resolveNote(id)
        }
        runCurrent()
        assertEquals(Screen.Note("stored-note", openedAsCurrent = true), navigation.current)
        assertEquals("Kept text", notes.text)
    }

    @Test fun aNewCurrentNotePublishesItsIdOnlyAfterSavingAndRestoresItWithAFreshOwner() =
        runTest(main.dispatcher + FrameClock) {
            var navigation by mutableStateOf(Navigation.restore(Navigation(Screen.Notes).open(Screen.Note(null)).saved))
            val notes = freshNotes()
            notes.open(null)
            runCurrent()
            val recomposer = Recomposer(coroutineContext)
            val runner = launch { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(EmptyApplier(), recomposer)
            val registry = SaveableStateRegistry(restoredValues = null) { true }
            var editorIdentity: Any? = null
            lateinit var editorState: MutableState<Int>
            var editorDisposals = 0
            var savedRoute = emptyList<String>()
            composition.setContent {
                val entry = navigation
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    val holder = rememberNavigationStateHolder(navigation)
                    holder.SaveableStateProvider(navigation.current.saveableKey) {
                        // These stand in for the editor's remembered FocusRequester/caret and scroll state.
                        editorIdentity = remember { Any() }
                        editorState = rememberSaveable { mutableStateOf(0) }
                        DisposableEffect(Unit) { onDispose { editorDisposals++ } }
                        ResolveSavedNoteRoute(notes) { id ->
                            if (navigation.owns(entry)) navigation = navigation.resolveNote(id)
                        }
                    }
                }
            }
            suspend fun settle() {
                Snapshot.sendApplyNotifications()
                runCurrent()
                recomposer.awaitIdle()
                runCurrent()
            }
            try {
                settle()
                val firstEditor = checkNotNull(editorIdentity)
                editorState.value = 7
                assertEquals("Untouched sample", notes.text)
                notes.flush()
                runCurrent()
                settle()
                assertNull(notes.savedId)
                assertEquals(Screen.Note(null), navigation.current)

                notes.edit("")
                notes.flush()
                runCurrent()
                settle()
                assertNull(notes.savedId)
                assertEquals(Screen.Note(null), navigation.current)

                val obstruction = File(folder.root, WriteDraftStore.FILE_NAME + ".tmp")
                assertTrue(obstruction.mkdir())
                File(obstruction, "keep").writeText("Blocks the first save")
                io.hold = true
                notes.edit("The newly saved note must keep this identity")
                notes.flush()
                runCurrent()
                settle()
                assertTrue(io.hasPendingWork)
                assertNull(notes.savedId)
                assertEquals(Screen.Note(null), navigation.current)
                io.release()
                runCurrent()
                settle()
                assertNull(notes.savedId)
                assertEquals(Screen.Note(null), navigation.current)

                assertTrue(obstruction.deleteRecursively())
                notes.retryStorage()
                runCurrent()
                settle()
                assertEquals("new-note", notes.savedId)
                assertEquals(Screen.Note("new-note", openedAsCurrent = true), navigation.current)
                assertEquals(Screen.Note(null).saveableKey, navigation.current.saveableKey)
                assertSame("First save must retain the live editor", firstEditor, editorIdentity)
                assertEquals(7, editorState.value)
                assertEquals(0, editorDisposals)
                savedRoute = navigation.saved
                val restoredRoute = Navigation.restore(savedRoute)
                assertEquals(navigation, restoredRoute)
                assertEquals(navigation.current.saveableKey, restoredRoute.current.saveableKey)

                navigation = navigation.open(Screen.Note("another-editor"))
                settle()
                assertNotSame("An explicit different note gets a new editor", firstEditor, editorIdentity)
                assertEquals(0, editorState.value)
                assertEquals(1, editorDisposals)
            } finally {
                composition.dispose()
                recomposer.cancel()
                runner.cancelAndJoin()
            }

            owners.single().clear()
            // Another Home host selects another durable note before this route is reconstructed.
            notesStore().saveCurrent(WriteDraft("other-note", "Another host", updatedAt = 20L))
            val otherHost = freshNotes()
            otherHost.open("other-note")
            runCurrent()
            assertEquals("other-note", notesStore().load().currentId)

            val restored = Navigation.restore(savedRoute)
            val fresh = freshNotes()
            assertNull(fresh.openId)
            fresh.open((restored.current as Screen.Note).id)
            runCurrent()
            assertEquals("new-note", fresh.openId)
            assertEquals("The newly saved note must keep this identity", fresh.text)
        }

    private object FrameClock : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            yield()
            return onFrame(System.nanoTime())
        }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private class HeldDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var hold = false
        private val pending = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        val hasPendingWork get() = pending.isNotEmpty()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (hold) pending.addLast(context to block) else delegate.dispatch(context, block)
        }
        fun release() {
            hold = false
            while (pending.isNotEmpty()) {
                val (context, block) = pending.removeFirst()
                delegate.dispatch(context, block)
            }
        }
    }
}
