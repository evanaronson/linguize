package com.evanaronson.linguize.ui.chat

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Deleting a conversation deletes its saved checks in History and what memory learned from it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatDeleteTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ChatStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deleted = CopyOnWriteArrayList<String>()
    private val forgotten = CopyOnWriteArrayList<String>()
    private val memory = object : UserMemory {
        override suspend fun forPrompt() = ""
        override suspend fun learnFrom(source: String, messages: List<MemoryMessage>) = Unit
        override suspend fun forget(source: String) { forgotten += source }
    }
    private val partner = ChatPartner(FakePreferences(), ProviderKeys { null }, emptyMap(), { memory }) {
        ChatPrompt.load { File("src/main/assets/$it").readText() }
    }
    private val chat = ChatViewModel(
        store = store,
        partner = partner,
        preferences = ChatPreferences(context),
        memory = { memory },
        startLanguage = { "es" },
        deleteSessions = { deleted += it },
        people = { emptyList() },
        translate = { error("not used") },
        warmTargets = {},
        appScope = scope,
    )

    @After fun close() {
        scope.cancel()
        store.close()
    }

    @Test fun deletingAConversationDeletesItsHistorySessionsAndForgetsIt() {
        runBlocking {
            store.create(Conversation("c1", "Weekend", "es", 1, 1))
            store.create(Conversation("c2", "Other", "es", 1, 1))
            store.add(ChatMessage.partner("p", "c1", "¡Hola!", 2))
            store.add(ChatMessage("w1", "c1", true, "Bien", "Bien", check = SentCheck.LooksGood, at = 3, sessionId = "s1"))
            store.add(ChatMessage("w2", "c1", true, "Vale", "Vale", check = SentCheck.AsWritten, at = 4, sessionId = "s2"))
            store.add(ChatMessage("w3", "c2", true, "Hola", "Hola", check = SentCheck.LooksGood, at = 5, sessionId = "s3"))
        }
        chat.delete("c1")
        val until = System.currentTimeMillis() + 5_000
        while ((deleted.size < 2 || forgotten.isEmpty()) && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(setOf("s1", "s2"), deleted.toSet())
        assertEquals(listOf("chat:c1"), forgotten.toList())
        runBlocking {
            assertNull(store.conversation("c1"))
            assertTrue(store.messages("c1").isEmpty())
            assertEquals(listOf("s3"), store.sessionIds("c2"))
        }
    }
}
