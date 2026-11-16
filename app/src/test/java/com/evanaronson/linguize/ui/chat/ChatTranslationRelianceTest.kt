package com.evanaronson.linguize.ui.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.ChatPartner
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.core.chat.ReplyRead
import com.evanaronson.linguize.core.memory.MemoryMessage
import com.evanaronson.linguize.core.memory.UserMemory
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.data.chat.ChatPreferences
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.llm.chat.ChatPrompt
import com.evanaronson.linguize.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lab, Translation reliance: the chat notes a reply's translation as needed only after it showed
 * long enough, on screen. Time is a fake monotonic clock; the view model's wake-up runs on the
 * test scheduler, advanced by hand (never to idle).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ChatTranslationRelianceTest {
    @get:Rule val main = MainDispatcherRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ChatStore(context, name = null, dispatcher = Dispatchers.Unconfined)
    private val scope = CoroutineScope(SupervisorJob() + main.dispatcher)
    private val memory = object : UserMemory {
        override suspend fun forPrompt() = ""
        override suspend fun learnFrom(source: String, messages: List<MemoryMessage>) = true
        override suspend fun forget(source: String) = Unit
    }
    private val partner = ChatPartner(FakePreferences(), ProviderKeys { null }, emptyMap(), { memory }, dispatcher = main.dispatcher) {
        ChatPrompt("chat", "{}")
    }
    private var elapsed = 0L
    private val chat = ChatViewModel(
        store = store,
        partner = partner,
        preferences = ChatPreferences(context),
        memory = { memory },
        startLanguage = { "es" },
        drainDeletions = {},
        people = { emptyList() },
        translate = { error("not used") },
        warmTargets = {},
        appScope = scope,
        clock = { 5_000L },
        monotonic = { elapsed },
    )
    private val reply = ChatMessage.partner("p1", "c1", "¿Qué tal?", 3, translation = "How are you?")

    @Before fun open() {
        runBlocking {
            store.create(Conversation("c1", "", "es", 1, 2))
            store.add(ChatMessage("w1", "c1", true, "Hola", "Hola", at = 2))
            store.add(reply)
        }
        chat.visible(true)
        chat.open("c1")
        run(0)
    }

    @After fun close() {
        scope.cancel()
        main.dispatcher.scheduler.runCurrent()
        store.close()
    }

    /** Time passes on both clocks. */
    private fun run(ms: Long) {
        elapsed += ms
        main.dispatcher.scheduler.advanceTimeBy(ms)
        main.dispatcher.scheduler.runCurrent()
    }

    private fun reads() = runBlocking { store.replyReads() }

    @Test fun aTranslationLeftOpenIsNotedOnceTheThresholdPasses() {
        chat.flip(reply)
        run(2_000)
        assertEquals(listOf(ReplyRead("p1", "es", 3)), reads())
        run(600)
        assertEquals(listOf(ReplyRead("p1", "es", 3, neededAt = 5_000)), reads())
    }

    @Test fun aQuickFlipBackIsNotNoted() {
        chat.flip(reply)
        run(800)
        chat.flip(reply)
        run(10_000)
        assertTrue(reads().single().neededAt == null)
    }

    @Test fun leavingTheChatPausesTheTranslationsTime() {
        chat.flip(reply)
        run(1_500)
        chat.visible(false)
        run(60_000)
        assertTrue(reads().single().neededAt == null)
        chat.visible(true)
        run(1_000)
        assertEquals(5_000L, reads().single().neededAt)
    }

    @Test fun aReplyScrolledOffScreenIsNotBeingRead() {
        chat.onScreen(setOf("w1"))
        chat.flip(reply)
        run(10_000)
        assertTrue(reads().single().neededAt == null)
        chat.onScreen(setOf("w1", "p1"))
        run(2_500)
        assertEquals(5_000L, reads().single().neededAt)
    }
}
