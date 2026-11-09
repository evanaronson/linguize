package com.evanaronson.linguize.ui.chat

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.ChatPartner
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.ChatStart
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

/** Lab, Language scope: the Chat list shows the current language's conversations, and new ones start in it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatScopeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ChatStore(context, name = null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memory = object : UserMemory {
        override suspend fun forPrompt() = ""
        override suspend fun learnFrom(source: String, messages: List<MemoryMessage>) = Unit
        override suspend fun forget(source: String) = Unit
    }
    private val partner = ChatPartner(FakePreferences(), ProviderKeys { null }, emptyMap(), { memory }) {
        ChatPrompt.load { File("src/main/assets/$it").readText() }
    }
    private val now = 100 * ChatStart.RESUME_WITHIN_MILLIS
    private var current: String? = "ca"
    private val switched = mutableListOf<String>()
    private val chat = ChatViewModel(
        store = store,
        partner = partner,
        preferences = ChatPreferences(context),
        memory = { memory },
        startLanguage = { "es" },
        deleteSessions = {},
        people = { emptyList() },
        translate = { error("not used") },
        warmTargets = {},
        appScope = scope,
        clock = { now },
        currentLanguage = { current },
        onLanguage = { switched += it },
    )

    @After fun close() {
        scope.cancel()
        store.close()
    }

    private fun conversation(id: String, language: String, at: Long) = runBlocking {
        store.create(Conversation(id, "Title $id", language, at, at))
        store.add(ChatMessage.partner("p-$id", id, "Hola", at))
        store.add(ChatMessage("w-$id", id, true, "Bé", "Bé", check = SentCheck.LooksGood, at = at))
    }

    private fun waitFor(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test fun theListShowsOnlyTheCurrentLanguagesConversations() {
        conversation("es1", "es", now - 1_000)
        conversation("ca1", "ca", now - 2_000)
        conversation("ca2", "ca", now - 3_000)
        chat.showList()
        waitFor { chat.conversations.size == 3 }
        assertEquals(listOf("ca1", "ca2"), ChatStart.inLanguage(chat.conversations, "ca").map { it.conversation.id })
        assertEquals(listOf("es1"), ChatStart.inLanguage(chat.conversations, "es").map { it.conversation.id })
        assertEquals(3, ChatStart.inLanguage(chat.conversations, null).size)
    }

    @Test fun theAppOpensTheCurrentLanguagesLatestConversation() {
        conversation("es1", "es", now - 1_000)
        conversation("ca1", "ca", now - 2_000)
        var resolved: String? = "unset"
        chat.openCurrent("ca") { resolved = it }
        waitFor { chat.thread != null }
        assertEquals("ca1", resolved)
        assertEquals("ca1", chat.thread!!.conversation.id)
        assertEquals("ca", switched.last())
    }

    @Test fun withNoCurrentConversationTheListShows() {
        // The only Catalan conversation went quiet long ago; nothing starts on its own.
        conversation("ca1", "ca", now - 2 * ChatStart.RESUME_WITHIN_MILLIS)
        conversation("es1", "es", now - 1_000)
        var resolved: String? = "unset"
        chat.openCurrent("ca") { resolved = it }
        waitFor { resolved != "unset" }
        assertNull(resolved)
        assertNull(chat.thread)
    }

    @Test fun aNewConversationStartsInTheCurrentLanguageAndKeepsIt() {
        current = "ca"
        val id = chat.newConversation()
        // Shown at once, in the app's language.
        assertEquals(id, chat.thread!!.conversation.id)
        assertEquals("ca", chat.thread!!.conversation.language)
        waitFor { runBlocking { store.conversation(id) } != null }
        assertEquals("ca", runBlocking { store.conversation(id) }!!.language)
        // One picked in the sheet wins, and becomes the app's language.
        val spanish = chat.newConversation(language = "es")
        assertEquals("es", chat.thread!!.conversation.language)
        assertEquals(spanish, chat.thread!!.conversation.id)
        assertTrue(switched.last() == "es")
    }

    @Test fun openingAConversationInAnotherLanguageMakesItTheAppsLanguage() {
        conversation("es1", "es", now - 1_000)
        chat.open("es1")
        waitFor { chat.thread != null }
        assertEquals("es", switched.last())
        var missing = false
        chat.open("gone", onMissing = { missing = true })
        waitFor { missing }
        assertTrue(missing)
    }
}
