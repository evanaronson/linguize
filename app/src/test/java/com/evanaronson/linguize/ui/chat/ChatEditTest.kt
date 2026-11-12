package com.evanaronson.linguize.ui.chat

import android.app.Application
import android.content.Context
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.ChatDeletions
import com.evanaronson.linguize.ChatPartner
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.core.chat.SendState
import com.evanaronson.linguize.core.chat.SentCheck
import com.evanaronson.linguize.core.memory.MemoryMessage
import com.evanaronson.linguize.core.memory.UserMemory
import com.evanaronson.linguize.core.time.DraftTiming
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.data.chat.ChatPreferences
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.chat.ChatPrompt
import com.evanaronson.linguize.testing.MainDispatcherRule
import com.evanaronson.linguize.ui.card.CardState
import kotlinx.coroutines.CompletableDeferred
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

/**
 * Editing a sent chat message: the conversation rewinds to just before it, every removed writer
 * message's History record, time record and memory go through the conversation-deletion
 * cleanup, and the text comes back to the composer to be sent through the usual check.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatEditTest {
    @get:Rule val main = MainDispatcherRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ChatStore(context, name = null, dispatcher = main.dispatcher)
    private val scope = CoroutineScope(SupervisorJob() + main.dispatcher)
    private val owners = ViewModelStore()
    private val historyDeleted = mutableListOf<String>()
    private val timingDeleted = mutableListOf<String>()
    private val forgotten = mutableListOf<String>()
    private var exportsForgotten = 0
    private val requests = mutableListOf<GenerationRequest>()
    private var gate: CompletableDeferred<Unit>? = null
    private val memory = object : UserMemory {
        override suspend fun forPrompt() = ""
        override suspend fun learnFrom(source: String, messages: List<MemoryMessage>) = true
        override suspend fun forget(source: String) { forgotten += source }
    }
    private val cleanup = ChatDeletions(store,
        forgetMemory = { memory.forget(it) },
        deleteHistory = { historyDeleted += it; true },
        deleteTiming = { timingDeleted += it; true },
        forgetExports = { exportsForgotten++; true },
    )
    private val card = object : DraftCheck {
        override var sessionId: String? = null
        val started = mutableListOf<Triple<String, Language, String?>>()
        override fun start(draft: String, language: Language, replyingTo: String?) {
            started += Triple(draft, language, replyingTo)
        }
        override fun stop() = Unit
        override fun finish(sent: String, timing: DraftTiming?): String? = sessionId
    }
    private val chat: ChatViewModel = run {
        val client = object : ProviderClient {
            override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
                requests += request
                gate?.await()
                return """{"reply":"Genial","title":"Plans","translation":"Great"}"""
            }
            override suspend fun models(key: String): List<String> = emptyList()
        }
        val partner = ChatPartner(FakePreferences(), ProviderKeys { "key" }, mapOf(Provider.Gemini to client), { memory },
            dispatcher = main.dispatcher) { ChatPrompt("chat", "{}") }
        ChatViewModel(
            store = store, partner = partner, preferences = ChatPreferences(context), memory = { memory },
            startLanguage = { "es" }, drainDeletions = { cleanup.drain() }, people = { emptyList() },
            translate = { error("not used") }, warmTargets = {}, appScope = scope, clock = { 100L },
        ).also {
            owners.put("chat", it)
            it.draftCheck = card
        }
    }

    @After fun close() {
        owners.clear()
        scope.cancel()
        main.dispatcher.scheduler.runCurrent()
        store.close()
    }

    /** Opener, then three exchanges: w1/p1, w2/p2, w3/p3, each writer message saved as History session s1..s3. */
    private suspend fun seed() {
        assertTrue(store.create(Conversation("c1", "Plans", "es", 1, 1, learnedCount = 7)))
        store.add(ChatMessage.partner("p0", "c1", "¿Qué tal?", 2))
        for (n in 1..3) {
            store.add(ChatMessage("w$n", "c1", true, "Raw $n", "Sent $n", check = SentCheck.LooksGood, at = 2L + n * 2, sessionId = "s$n"))
            store.add(ChatMessage.partner("p$n", "c1", "Reply $n", 3L + n * 2))
        }
    }

    private fun clean(text: String) = CardState.Done(CheckResult.Reviewed(Revision(text, emptyList()), listOf(EditKind.Fix, EditKind.Natural)))

    @Test fun editingTheLastSentMessageRewindsWithoutAskingAndPutsItsTextInTheComposer() = runTest(main.dispatcher) {
        seed()
        chat.open("c1")
        runCurrent()
        assertTrue(chat.canEdit)

        chat.edit("w3")
        // Only partner replies follow it: no confirmation.
        assertNull(chat.editConfirmation)
        runCurrent()

        val left = listOf("p0", "w1", "p1", "w2", "p2")
        assertEquals(left, store.messages("c1").map { it.id })
        assertEquals(left, chat.thread!!.messages.map { it.id })
        assertEquals("Sent 3", chat.draft.text)
        assertEquals(6, chat.draft.selection.start)
        assertTrue(chat.focusDraft)
        assertEquals(SendState.Composing, chat.send)
        // Its History record and time record went, through the conversation-deletion cleanup.
        assertEquals(listOf("s3"), historyDeleted)
        assertEquals(listOf("s3"), timingDeleted)
        assertEquals(1, exportsForgotten)
        assertTrue(store.pendingDeletions().entries.isEmpty())
        // Memory forgets the conversation and learns it again from what remains.
        assertTrue(forgotten.isNotEmpty() && forgotten.all { it == "chat:c1" })
        assertEquals(0, store.conversation("c1")!!.learnedCount)
        // The other conversation data stays.
        assertEquals(listOf("s1", "s2"), store.sessionIds("c1").sorted())
    }

    @Test fun editingAnEarlierMessageAsksFirstAndCancelChangesNothing() = runTest(main.dispatcher) {
        seed()
        chat.open("c1")
        runCurrent()
        val before = store.messages("c1")

        chat.edit("w1")
        runCurrent()
        assertEquals(EditConfirmation("c1", "w1", 3), chat.editConfirmation)
        assertEquals(before, store.messages("c1"))

        chat.cancelEdit()
        runCurrent()
        assertNull(chat.editConfirmation)
        assertEquals(before, store.messages("c1"))
        assertEquals(before, chat.thread!!.messages)
        assertEquals("", chat.draft.text)
        assertTrue(historyDeleted.isEmpty())
        assertTrue(timingDeleted.isEmpty())
        assertTrue(forgotten.isEmpty())
        assertTrue(store.pendingDeletions().entries.isEmpty())
    }

    @Test fun confirmingDeletesEveryLaterMessageAndEachOfTheirHistoryRecords() = runTest(main.dispatcher) {
        seed()
        chat.open("c1")
        runCurrent()

        chat.edit("w2")
        assertEquals(2, chat.editConfirmation!!.checks)
        chat.confirmEdit()
        runCurrent()

        assertNull(chat.editConfirmation)
        assertEquals(listOf("p0", "w1", "p1"), chat.thread!!.messages.map { it.id })
        assertEquals(listOf("p0", "w1", "p1"), store.messages("c1").map { it.id })
        assertEquals("Sent 2", chat.draft.text)
        assertEquals(setOf("s2", "s3"), historyDeleted.toSet())
        assertEquals(setOf("s2", "s3"), timingDeleted.toSet())
        assertEquals(listOf("s1"), store.sessionIds("c1"))
        assertTrue(store.pendingDeletions().entries.isEmpty())
    }

    @Test fun theEditedTextSendsThroughTheUsualCheckAndThePartnerRepliesAfresh() = runTest(main.dispatcher) {
        seed()
        chat.open("c1")
        runCurrent()
        chat.edit("w3")
        runCurrent()
        val asked = requests.size

        chat.onDraftChange(TextFieldValue("Sent 3, edited"))
        chat.send()
        // The same check as any new message, as a reply to the partner's last message.
        assertEquals(Triple("Sent 3, edited", Language.Spanish, "Reply 2"), card.started.last())
        assertEquals(SendState.Checking("Sent 3, edited"), chat.send)
        chat.onCardState(clean("Sent 3, edited"))
        runCurrent()

        val messages = store.messages("c1")
        assertEquals(listOf("p0", "w1", "p1", "w2", "p2"), messages.take(5).map { it.id })
        assertEquals("Sent 3, edited", messages[5].text)
        assertTrue(messages[5].fromWriter)
        assertEquals("Genial", messages[6].text)
        assertEquals(asked + 1, requests.size)
        assertEquals("", chat.draft.text)
    }

    @Test fun partnerRepliesAreNotEditable() = runTest(main.dispatcher) {
        seed()
        chat.open("c1")
        runCurrent()
        val before = store.messages("c1")

        chat.edit("p2")
        runCurrent()

        assertNull(chat.editConfirmation)
        assertEquals(before, store.messages("c1"))
        assertEquals("", chat.draft.text)
        assertTrue(historyDeleted.isEmpty())
    }

    @Test fun editIsUnavailableWhileAMessageIsCheckedOrTheReplyIsComing() = runTest(main.dispatcher) {
        seed()
        chat.open("c1")
        runCurrent()
        val before = store.messages("c1")

        // While the message is checked.
        chat.onDraftChange(TextFieldValue("Otra cosa"))
        chat.send()
        assertFalse(chat.canEdit)
        chat.edit("w3")
        runCurrent()
        assertNull(chat.editConfirmation)
        assertEquals(before, store.messages("c1"))

        // While the partner writes its reply.
        gate = CompletableDeferred()
        chat.onCardState(clean("Otra cosa"))
        runCurrent()
        assertTrue(chat.replying)
        assertFalse(chat.canEdit)
        chat.edit("w3")
        chat.edit("w1")
        runCurrent()
        assertNull(chat.editConfirmation)
        assertEquals(before.size + 1, store.messages("c1").size)
        assertTrue(historyDeleted.isEmpty())

        // Once the turn is over, it is back.
        gate!!.complete(Unit)
        runCurrent()
        assertFalse(chat.replying)
        assertTrue(chat.canEdit)
    }

    @Test fun aFailedRewindKeepsTheConversationAndOffersRetry() = runTest(main.dispatcher) {
        seed()
        chat.open("c1")
        runCurrent()
        // Another screen removed the message: nothing to rewind.
        store.rewind("c1", "w3")
        historyDeleted.clear()

        chat.edit("w3")
        runCurrent()

        assertEquals(ConversationWriteFailure.Rewind("c1", "w3"), chat.conversationSaveFailure)
        assertEquals("", chat.draft.text)
        assertFalse(chat.rewinding)
    }
}
