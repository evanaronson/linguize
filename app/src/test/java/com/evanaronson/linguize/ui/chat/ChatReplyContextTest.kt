package com.evanaronson.linguize.ui.chat

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.ChatPartner
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.core.memory.MemoryMessage
import com.evanaronson.linguize.core.memory.UserMemory
import com.evanaronson.linguize.core.time.DraftTiming
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.data.chat.ChatPreferences
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.llm.chat.ChatPrompt
import com.evanaronson.linguize.llm.lab.ReplyPrompt
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

/** Lab: every chat check goes with the partner's last message as context; no other host sends one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatReplyContextTest {
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
    private val now = 1_000_000L
    private val chat = ChatViewModel(
        store = store,
        partner = partner,
        memory = { memory },
        preferences = ChatPreferences(context),
        startLanguage = { "es" },
        deleteSessions = {},
        people = { emptyList() },
        translate = { error("not used") },
        warmTargets = {},
        appScope = scope,
        clock = { now },
    )

    /** The card, faked: what each check was started with. */
    private val card = object : DraftCheck {
        val started = mutableListOf<Pair<String, String?>>()
        override fun start(draft: String, language: Language, replyingTo: String?) { started += draft to replyingTo }
        override fun stop() = Unit
        override fun finish(sent: String, timing: DraftTiming?): String? = null
    }

    @After fun close() {
        scope.cancel()
        store.close()
    }

    @Test fun thePartnersLastMessageIsTheContext() {
        val messages = listOf(
            partner("1", "¡Hola! ¿Qué tal el finde?", 1),
            writer("2", "Bien, fui a la playa", 2),
            partner("3", "  ¡Qué bien! ¿Con quién fuiste?  ", 3),
            writer("4", "Con mi hermana", 4),
        )
        assertEquals("¡Qué bien! ¿Con quién fuiste?", ChatReplyContext.of(messages))
        assertNull(ChatReplyContext.of(messages.filter { it.fromWriter }))
        assertNull(ChatReplyContext.of(emptyList()))
        assertNull(ChatReplyContext.of(listOf(partner("5", "   ", 5))))
        val long = "a".repeat(ReplyPrompt.MAX_CHARS) + " fin?"
        assertEquals(ReplyPrompt.MAX_CHARS, ChatReplyContext.of(listOf(partner("6", long, 6)))!!.length)
        assertTrue(ChatReplyContext.of(listOf(partner("6", long, 6)))!!.endsWith("fin?"))
    }

    @Test fun sendingChecksTheDraftWithThePartnersLastMessage() {
        val conversation = Conversation("c", "", "es", now, now)
        runBlocking {
            store.create(conversation)
            store.add(partner("1", "¿Le va bien el martes?", now - 10))
        }
        chat.draftCheck = card
        chat.open("c")
        val until = System.currentTimeMillis() + 5_000
        while (chat.thread?.messages?.isEmpty() != false && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        chat.onDraftChange(TextFieldValue("Sí, a las cinco me va bien"))
        chat.send()
        assertEquals(listOf("Sí, a las cinco me va bien" to "¿Le va bien el martes?"), card.started)
    }

    @Test fun onlyTheChatSendsAReplyContext() {
        // The selection menu, the accessibility popup and Write never pass one: they check the text alone.
        val sources = File("src/main/java/com/evanaronson/linguize").walkTopDown().filter { it.extension == "kt" }
        val passing = sources.filter { "replyingTo" in it.readText() }.map { it.relativeTo(File("src/main/java/com/evanaronson/linguize")).path }
            .toSet()
        assertEquals(setOf("Checker.kt", "ui/card/CheckViewModel.kt", "ui/chat/ChatScreen.kt", "ui/chat/ChatViewModel.kt"), passing)
    }

    private fun partner(id: String, text: String, at: Long) = ChatMessage.partner(id, "c", text, at)

    private fun writer(id: String, text: String, at: Long) =
        ChatMessage(id, "c", fromWriter = true, raw = text, text = text, at = at)
}
