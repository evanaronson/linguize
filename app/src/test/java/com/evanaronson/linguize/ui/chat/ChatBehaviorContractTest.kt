package com.evanaronson.linguize.ui.chat

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.ChatPartner
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.chat.ChatChange
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
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.chat.ChatPrompt
import com.evanaronson.linguize.testing.KnownBonanzaDefect
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real chat coordinator and SQLite store, with scheduled IO and a gated provider. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatBehaviorContractTest {
    @get:Rule val main = MainDispatcherRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ChatStore(context, name = null, dispatcher = main.dispatcher)
    private val scope = CoroutineScope(SupervisorJob() + main.dispatcher)
    private val owners = ViewModelStore()
    private val requests = mutableListOf<GenerationRequest>()
    private val memory = object : UserMemory {
        override suspend fun forPrompt() = ""
        override suspend fun learnFrom(source: String, messages: List<MemoryMessage>) = Unit
        override suspend fun forget(source: String) = Unit
    }
    private val card = object : DraftCheck {
        val started = mutableListOf<Triple<String, Language, String?>>()
        val finished = mutableListOf<String>()
        var stops = 0
        override fun start(draft: String, language: Language, replyingTo: String?) {
            started += Triple(draft, language, replyingTo)
        }
        override fun stop() { stops++ }
        override fun finish(sent: String, timing: DraftTiming?): String? {
            finished += sent
            return null // The real DraftCheck contract when History is off.
        }
    }

    @After fun close() {
        owners.clear()
        scope.cancel()
        main.dispatcher.scheduler.runCurrent()
        store.close()
    }

    private fun model(
        store: ChatStore = this.store,
        startLanguage: suspend () -> String = { "es" },
        answer: suspend () -> String = { """{"reply":"¡Hola!","title":"Greeting","translation":"Hi!"}""" },
    ): ChatViewModel {
        val client = object : ProviderClient {
            override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
                requests += request
                return answer()
            }
            override suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer = error("unused")
            override suspend fun models(key: String): List<String> = emptyList()
        }
        val partner = ChatPartner(
            FakePreferences(), ProviderKeys { "key" }, mapOf(Provider.Gemini to client), { memory },
            dispatcher = main.dispatcher,
        ) { ChatPrompt("chat", "{}") }
        return ChatViewModel(
            store = store, partner = partner, preferences = ChatPreferences(context), memory = { memory },
            startLanguage = startLanguage, deleteSessions = {}, people = { emptyList() },
            translate = { error("Bundled translations must not need another request") }, warmTargets = {},
            appScope = scope, clock = { 10L }, currentLanguage = { null },
        ).also {
            owners.put("chat", it)
            it.draftCheck = card
        }
    }

    private suspend fun seed(id: String = "thread", title: String = "", custom: Boolean = false, writerLast: Boolean = false) {
        assertTrue(store.create(Conversation(id, title, "es", 1, 2, titleIsCustom = custom)))
        assertTrue(store.add(ChatMessage.partner("opener-$id", id, "¿Qué tal?", 2)))
        if (writerLast) assertTrue(store.add(ChatMessage("writer-$id", id, true, "Bien", "Bien", at = 3)))
    }

    private fun clean(text: String) = CardState.Done(
        CheckResult.Reviewed(Revision(text, emptyList()), listOf(EditKind.Fix, EditKind.Natural)),
    )

    @Test fun cleanChecksSendAutomaticallyAndPersistWithHistoryOff() = runTest(main.dispatcher) {
        seed()
        val chat = model()
        chat.open("thread")
        runCurrent()
        chat.onDraftChange(TextFieldValue("  Muy bien  "))
        chat.send()
        assertEquals(listOf(Triple("Muy bien", Language.Spanish, "¿Qué tal?")), card.started)
        assertEquals(1, store.messages("thread").size)

        chat.onCardState(clean("Muy bien"))
        runCurrent()
        val messages = store.messages("thread")
        val sent = messages.single { it.fromWriter }
        assertEquals("Muy bien", sent.raw)
        assertEquals("Muy bien", sent.text)
        assertEquals(SentCheck.LooksGood, sent.check)
        assertTrue(sent.changes.isEmpty())
        assertNull(sent.sessionId)
        assertEquals(listOf("Muy bien"), card.finished)
        assertEquals("", chat.draft.text)
        assertEquals(SendState.Composing, chat.send)
        assertEquals(messages, chat.thread!!.messages)
        assertEquals("Hi!", messages.last().translation)
        chat.flip(messages.last())
        assertEquals(Flipped.Text("Hi!"), chat.flippedView(messages.last()))
        assertEquals(1, requests.size)
    }

    @Test fun reviewedSubsetKeepsRawTextAndOnlyAcceptedChangesAfterReopening() = runTest(main.dispatcher) {
        seed()
        val chat = model()
        chat.open("thread")
        runCurrent()
        val raw = "Ayer voy a casa"
        val fix = Edit(0, EditKind.Fix, 5, 8, "voy", "fui", "Past tense")
        val other = Edit(1, EditKind.Natural, 11, 15, "casa", "mi casa", "More specific")
        val revision = Revision(raw, listOf(fix, other))
        chat.onDraftChange(TextFieldValue(raw))
        chat.send()
        chat.onCardState(CardState.Done(CheckResult.Reviewed(revision, listOf(EditKind.Fix, EditKind.Natural))))
        runCurrent()
        assertEquals(SendState.Reviewing(raw), chat.send)
        assertTrue(requests.isEmpty())
        assertEquals(1, store.messages("thread").size)

        chat.sendReviewed(revision.accept(fix.id))
        runCurrent()
        val reopened = model()
        reopened.open("thread")
        runCurrent()
        val sent = reopened.thread!!.messages.single { it.fromWriter }
        assertEquals(raw, sent.raw)
        assertEquals("Ayer fui a casa", sent.text)
        assertEquals(SentCheck.Corrected, sent.check)
        assertEquals(listOf(ChatChange(EditKind.Fix, 5, 8, "voy", "fui", "Past tense")), sent.changes)
        assertNull(sent.sessionId)
        assertEquals(listOf("Ayer fui a casa"), card.finished)
    }

    @Test fun failedAndUnclearChecksKeepTheDraftWithoutSending() = runTest(main.dispatcher) {
        seed()
        val chat = model()
        chat.open("thread")
        runCurrent()
        val answers = listOf(CardState.Failed(CheckFailure.Reason.Offline, null), CardState.Done(CheckResult.Unclear))
        for (answer in answers) {
            chat.onDraftChange(TextFieldValue("Mi borrador"))
            chat.send()
            chat.onCardState(answer)
            runCurrent()
            assertEquals(SendState.Reviewing("Mi borrador"), chat.send)
            assertEquals("Mi borrador", chat.draft.text)
            assertEquals(1, store.messages("thread").size)
            assertTrue(requests.isEmpty())
            assertTrue(card.finished.isEmpty())
            val stops = card.stops
            chat.editDraft()
            assertEquals(SendState.Composing, chat.send)
            assertEquals("Mi borrador", chat.draft.text)
            assertEquals(stops + 1, card.stops)
        }
        // The Compose card owns whether its Send action is available; this covers the coordinator's
        // response/Edit message path, not a claim that calling sendReviewed directly is gated here.
    }

    @Test fun anExistingManualTitleSurvivesAProviderTitleAndReopening() = runTest(main.dispatcher) {
        seed(title = "My title", custom = true, writerLast = true)
        val answer = CompletableDeferred<String>()
        val chat = model(answer = { answer.await() })
        chat.open("thread")
        runCurrent()
        chat.retryReply()
        runCurrent()
        assertTrue(chat.replying)
        assertEquals(1, requests.size)
        answer.complete("""{"reply":"Bien!","title":"Automatic title","translation":"Good!"}""")
        runCurrent()
        val reopened = model()
        reopened.open("thread")
        runCurrent()
        assertEquals("My title", reopened.thread!!.conversation.title)
        assertTrue(reopened.thread!!.conversation.titleIsCustom)
        assertEquals("Good!", reopened.thread!!.messages.last().translation)
    }

    @Test
    @Category(KnownBonanzaDefect::class)
    fun aFailedWriterSaveKeepsTheDraftAndDoesNotRequestAReply() = runTest(main.dispatcher) {
        val name = "chat-save-failure"
        context.deleteDatabase(name)
        val failingStore = ChatStore(context, name = name, dispatcher = main.dispatcher)
        try {
            assertTrue(failingStore.create(Conversation("thread", "", "es", 1, 2)))
            assertTrue(failingStore.add(ChatMessage.partner("opener", "thread", "¿Qué tal?", 2)))
            val chat = model(store = failingStore)
            chat.open("thread")
            runCurrent()
            assertEquals(1, chat.thread!!.messages.size)
            // A real SQLite write fault, below the store's Boolean acknowledgement boundary.
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE messages")
            }
            assertFalse(failingStore.add(ChatMessage("probe", "thread", true, "Probe", "Probe", at = 3)))
            chat.onDraftChange(TextFieldValue("Do not lose this draft"))
            chat.send()
            chat.onCardState(clean("Do not lose this draft"))
            runCurrent()

            assertEquals("A rejected write must leave the writer's text recoverable", "Do not lose this draft", chat.draft.text)
            assertTrue("The check must not finish as sent before the chat write succeeds", card.finished.isEmpty())
            assertTrue("The partner must not answer an unsaved message", requests.isEmpty())
        } finally {
            owners.clear()
            runCurrent()
            failingStore.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    @Category(KnownBonanzaDefect::class)
    fun openingAnAutomaticallyCreatedConversationWaitsForItsPendingCreation() = runTest(main.dispatcher) {
        val language = CompletableDeferred<String>()
        var resolving = false
        val chat = model(startLanguage = { resolving = true; language.await() })
        val id = chat.newConversation()
        runCurrent()
        assertTrue(resolving)
        assertNull(store.conversation(id))
        var missing = false
        // ChatListScreen navigates with this ID immediately, then ChatThreadScreen calls open.
        chat.open(id) { missing = true }
        runCurrent()
        val missingWhileCreating = missing
        language.complete("es")
        runCurrent()
        assertNotNull(store.conversation(id))
        assertEquals(id, chat.thread!!.conversation.id)
        assertFalse("A pending local creation is not a missing conversation", missingWhileCreating)
        assertFalse(missing)
    }

    @Test
    @Category(KnownBonanzaDefect::class)
    fun aManualTitleSetDuringAReplySurvivesInStorage() = runTest(main.dispatcher) {
        seed(writerLast = true)
        val answer = CompletableDeferred<String>()
        val chat = model(answer = { answer.await() })
        chat.open("thread")
        runCurrent()
        chat.retryReply()
        runCurrent()
        assertEquals(1, requests.size)
        chat.rename("thread", "My manual title")
        runCurrent()
        assertEquals("My manual title", store.conversation("thread")!!.title)
        assertTrue(store.conversation("thread")!!.titleIsCustom)
        answer.complete("""{"reply":"Hola!","title":"Automatic title","translation":"Hi!"}""")
        runCurrent()
        assertFalse(chat.replying)
        assertEquals("My manual title", chat.thread!!.conversation.title)
        val stored = store.conversation("thread")!!
        assertEquals("The manual title must survive a fresh load", "My manual title", stored.title)
        assertTrue(stored.titleIsCustom)
    }

    @Test
    @Category(KnownBonanzaDefect::class)
    fun cancellingAnOlderReplyDoesNotClearTheNewReplyStatus() = runTest(main.dispatcher) {
        seed()
        val first = CompletableDeferred<String>()
        val second = CompletableDeferred<String>()
        val chat = model(answer = { if (requests.size == 1) first.await() else second.await() })
        chat.open("thread")
        runCurrent()
        chat.onDraftChange(TextFieldValue("Primero"))
        chat.send()
        chat.onCardState(clean("Primero"))
        runCurrent()
        assertTrue(chat.replying)
        assertEquals(1, requests.size)
        // Consecutive sends are an existing product feature; the newer request supersedes the first.
        chat.onDraftChange(TextFieldValue("Segundo"))
        chat.send()
        chat.onCardState(clean("Segundo"))
        runCurrent()
        assertEquals(2, requests.size)
        assertEquals(listOf("Primero", "Segundo"), store.messages("thread").filter { it.fromWriter }.map { it.text })
        val replyingWhileSecondPending = chat.replying
        second.complete("""{"reply":"Entendido","title":"Conversation"}""")
        runCurrent()
        assertEquals("Entendido", chat.thread!!.messages.last().text)
        assertFalse(chat.replying)
        assertTrue("Only the active reply may clear its loading status", replyingWhileSecondPending)
    }

    @Test
    @Category(KnownBonanzaDefect::class)
    fun delayedCreationDoesNotReplaceANewerSelectionAndItsDraft() = runTest(main.dispatcher) {
        seed(id = "existing", writerLast = true)
        val language = CompletableDeferred<String>()
        val chat = model(startLanguage = { language.await() })
        val created = chat.newConversation()
        runCurrent()
        chat.open("existing")
        runCurrent()
        chat.onDraftChange(TextFieldValue("Still writing here"))
        assertEquals("existing", chat.thread!!.conversation.id)
        language.complete("es")
        runCurrent()
        assertNotNull(store.conversation(created))
        assertEquals("The latest selected conversation must remain visible", "existing", chat.thread!!.conversation.id)
        assertEquals("Still writing here", chat.draft.text)
    }
}
