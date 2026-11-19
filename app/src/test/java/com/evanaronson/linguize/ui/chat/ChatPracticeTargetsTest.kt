package com.evanaronson.linguize.ui.chat

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
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
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Lab Chat, practice targets in the conversation: they start building as soon as Chat shows or
 * a conversation opens, in its language; and the target a reply says it aims at is stored with
 * it, offered back to vary the next conversation, and never shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatPracticeTargetsTest {
    @get:Rule val main = MainDispatcherRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ChatStore(context, name = null, dispatcher = main.dispatcher)
    private val scope = CoroutineScope(SupervisorJob() + main.dispatcher)
    private val owners = ViewModelStore()
    private val requests = mutableListOf<GenerationRequest>()
    private val warmed = mutableListOf<String>()
    private var current: String? = "ca"
    private val now = 100 * ChatStart.RESUME_WITHIN_MILLIS
    private val memory = object : UserMemory {
        override suspend fun forPrompt() = ""
        override suspend fun learnFrom(source: String, messages: List<MemoryMessage>) = true
        override suspend fun forget(source: String) = Unit
    }
    private val chat: ChatViewModel = run {
        val client = object : ProviderClient {
            override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
                requests += request
                return """{"reply":"Què faràs aquest cap de setmana?","translation":"What will you do this weekend?","title":"","aiming_at":"future, I"}"""
            }
            override suspend fun models(key: String): List<String> = emptyList()
        }
        val partner = ChatPartner(FakePreferences(), ProviderKeys { "key" }, mapOf(Provider.Gemini to client), { memory },
            targets = { "- Verb forms they rarely or never write: future (I, we)." }, dispatcher = main.dispatcher) {
            ChatPrompt.load { File("src/main/assets/$it").readText() }
        }
        ChatViewModel(
            store = store, partner = partner, preferences = ChatPreferences(context), memory = { memory },
            startLanguage = { "es" }, drainDeletions = {}, people = { emptyList() },
            translate = { error("not used") }, warmTargets = { warmed += it.code }, appScope = scope, clock = { now },
            currentLanguage = { current },
        ).also { owners.put("chat", it) }
    }

    @After fun close() {
        owners.clear()
        scope.cancel()
        main.dispatcher.scheduler.runCurrent()
        store.close()
    }

    private suspend fun conversation(id: String, language: String, at: Long) {
        store.create(Conversation(id, "Title $id", language, at, at))
        store.add(ChatMessage.partner("p-$id", id, "Hola", at))
        store.add(ChatMessage("w-$id", id, true, "Bé", "Bé", check = SentCheck.LooksGood, at = at + 1))
    }

    @Test fun chatShowingWarmsTheAppLanguageAndFollowsItsChanges() = runTest(main.dispatcher) {
        chat.showList()
        assertEquals(listOf("ca"), warmed)
        current = "es"
        chat.showList()
        assertEquals(listOf("ca", "es"), warmed)
        // Under Linguize there is no one language to warm.
        current = null
        chat.showList()
        assertEquals(listOf("ca", "es"), warmed)
    }

    @Test fun openingAConversationWarmsItsLanguageBeforeItsThreadIsRead() = runTest(main.dispatcher) {
        conversation("es1", "es", now - 1_000)
        current = null
        chat.showList()
        runCurrent()
        warmed.clear()
        chat.open("es1")
        // At once, from the list, before the thread is read.
        assertEquals(listOf("es"), warmed)
        runCurrent()
        assertEquals("es1", chat.thread!!.conversation.id)
        assertTrue(warmed.all { it == "es" })
    }

    @Test fun theCurrentConversationIsWarmedInTheRequestedLanguage() = runTest(main.dispatcher) {
        conversation("ca1", "ca", now - 1_000)
        chat.openCurrent("ca") {}
        assertEquals(listOf("ca"), warmed)
        runCurrent()
        assertEquals("ca1", chat.thread!!.conversation.id)
        assertTrue(warmed.all { it == "ca" })
    }

    @Test fun aNewConversationWarmsItsLanguage() = runTest(main.dispatcher) {
        current = "es"
        chat.newConversation(language = "ca")
        assertEquals("ca", warmed.first())
        runCurrent()
    }

    @Test fun whatAReplyAimsAtIsStoredNeverShownAndVariesTheNextConversation() = runTest(main.dispatcher) {
        val first = chat.newConversation(language = "ca")
        runCurrent()
        val opener = store.messages(first).single()
        assertEquals("future, I", opener.aimingAt)
        // On screen: the reply and its translation, nothing of the aim.
        val shown = chat.thread!!.messages.single()
        assertEquals("Què faràs aquest cap de setmana?", shown.text)
        assertFalse(shown.text.contains("future") || shown.translation.orEmpty().contains("future, I"))
        assertNull(requests.single().system.lineSequence().firstOrNull { it.startsWith("- Worked on in your last conversation") })

        // The writer answered, so the conversation stays when the next one starts.
        store.add(ChatMessage("w-first", first, true, "Bé", "Bé", check = SentCheck.LooksGood, at = now + 1))
        // The next conversation in the language is told what the last one worked on.
        chat.newConversation(language = "ca")
        runCurrent()
        assertTrue(requests.last().system.contains("- Worked on in your last conversation: future, I.\n"))
        // Another language's aims never cross over.
        chat.newConversation(language = "es")
        runCurrent()
        assertFalse(requests.last().system.contains("Worked on in your last conversation"))
    }

    @Test fun noScreenReadsTheAim() {
        val ui = File("src/main/java/com/evanaronson/linguize/ui")
        val readers = ui.walk().filter { it.isFile && it.extension == "kt" && it.readText().contains("aimingAt") }.map { it.name }.toList()
        // The view model only passes the answer's aim on to the stored message.
        assertEquals(listOf("ChatViewModel.kt"), readers)
        val uses = File(ui, "chat/ChatViewModel.kt").readLines().filter { "aimingAt" in it }.map { it.trim() }
        assertEquals(listOf("answer.aimingAt)", "answer.aimingAt)"), uses)
    }
}
