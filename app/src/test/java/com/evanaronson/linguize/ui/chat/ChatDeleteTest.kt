package com.evanaronson.linguize.ui.chat

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.ChatDeletions
import com.evanaronson.linguize.ChatPartner
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.core.chat.SentCheck
import com.evanaronson.linguize.core.memory.MemoryMessage
import com.evanaronson.linguize.core.memory.UserMemory
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.data.chat.ChatPreferences
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.llm.chat.ChatPrompt
import com.evanaronson.linguize.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Deleting a conversation queues and acknowledges only its own linked cleanup. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatDeleteTest {
    @get:Rule val main = MainDispatcherRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ChatStore(context, name = null, dispatcher = main.dispatcher)
    private val scope = CoroutineScope(SupervisorJob() + main.dispatcher)
    private val owners = ViewModelStore()
    private val deleted = mutableListOf<String>()
    private var historyAvailable = true
    private val forgotten = mutableListOf<String>()
    private val timing = mutableListOf<String>()
    private val memory = object : UserMemory {
        override suspend fun forPrompt() = ""
        override suspend fun learnFrom(source: String, messages: List<MemoryMessage>) = true
        override suspend fun forget(source: String) { forgotten += source }
    }
    private val partner = ChatPartner(FakePreferences(), ProviderKeys { null }, emptyMap(), { memory }, dispatcher = main.dispatcher) {
        ChatPrompt.load { File("src/main/assets/$it").readText() }
    }
    private val cleanup = ChatDeletions(store,
        forgetMemory = { memory.forget(it) },
        deleteHistory = { assertTrue(forgotten.contains("chat:c1")); deleted += it; historyAvailable },
        deleteTiming = { timing += it; true },
    )
    private val chat = ChatViewModel(
        store = store,
        partner = partner,
        preferences = ChatPreferences(context),
        memory = { memory },
        startLanguage = { "es" },
        drainDeletions = { cleanup.drain() },
        people = { emptyList() },
        translate = { error("not used") },
        warmTargets = {},
        appScope = scope,
    ).also { owners.put("chat", it) }

    @After fun close() {
        owners.clear()
        scope.cancel()
        main.dispatcher.scheduler.runCurrent()
        store.close()
    }

    @Test fun deletingAConversationDeletesItsHistorySessionsAndForgetsIt() = runTest(main.dispatcher) {
        store.create(Conversation("c1", "Weekend", "es", 1, 1))
        store.create(Conversation("c2", "Other", "es", 1, 1))
        store.add(ChatMessage.partner("p", "c1", "¡Hola!", 2))
        store.add(ChatMessage("w1", "c1", true, "Bien", "Bien", check = SentCheck.LooksGood, at = 3, sessionId = "s1"))
        store.add(ChatMessage("w2", "c1", true, "Vale", "Vale", check = SentCheck.AsWritten, at = 4, sessionId = "s2"))
        store.add(ChatMessage("w3", "c2", true, "Hola", "Hola", check = SentCheck.LooksGood, at = 5, sessionId = "s3"))

        chat.delete("c1")
        runCurrent()

        assertEquals(setOf("s1", "s2"), deleted.toSet())
        assertEquals(setOf("chat:c1"), forgotten.toSet())
        assertEquals(setOf("s1", "s2"), timing.toSet())
        assertNull(store.conversation("c1"))
        assertTrue(store.messages("c1").isEmpty())
        assertTrue(store.pendingDeletions().entries.isEmpty())
        assertEquals(listOf("s3"), store.sessionIds("c2"))
    }

    @Test fun deletionContinuesAfterTheViewModelIsCleared() = runTest(main.dispatcher) {
        store.create(Conversation("c1", "Weekend", "es", 1, 1))
        store.add(ChatMessage("w1", "c1", true, "Bien", "Bien", at = 2, sessionId = "s1"))

        chat.delete("c1")
        owners.clear()
        runCurrent()

        assertNull(store.conversation("c1"))
        assertEquals(setOf("s1"), deleted.toSet())
        assertEquals(setOf("chat:c1"), forgotten.toSet())
        assertTrue(store.pendingDeletions().entries.isEmpty())
    }

    @Test fun returningToTheListRetriesFailedCleanupWithoutAnotherDeletion() = runTest(main.dispatcher) {
        store.create(Conversation("c1", "Weekend", "es", 1, 1))
        store.add(ChatMessage("w1", "c1", true, "Bien", "Bien", at = 2, sessionId = "s1"))
        historyAvailable = false

        chat.delete("c1")
        runCurrent()

        assertNull(store.conversation("c1"))
        assertEquals(listOf("s1"), store.pendingDeletions().entries.single().sessionIds)
        val attemptsBeforeReturn = deleted.size
        assertTrue(attemptsBeforeReturn > 0)
        historyAvailable = true

        chat.showList()
        runCurrent()

        assertTrue(deleted.size > attemptsBeforeReturn)
        assertTrue(store.pendingDeletions().entries.isEmpty())
        assertEquals(setOf("s1"), timing.toSet())
    }
}
