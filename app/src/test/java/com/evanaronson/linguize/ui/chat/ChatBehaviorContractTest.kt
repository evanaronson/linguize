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
import com.evanaronson.linguize.core.chat.Outgoings
import com.evanaronson.linguize.core.chat.SendState
import com.evanaronson.linguize.core.chat.SentCheck
import com.evanaronson.linguize.core.memory.MemoryMessage
import com.evanaronson.linguize.core.memory.UserMemory
import com.evanaronson.linguize.core.time.DraftTiming
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.data.chat.ChatPreferences
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.chat.ChatPrompt
import com.evanaronson.linguize.testing.MainDispatcherRule
import com.evanaronson.linguize.ui.card.CardState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

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
        override suspend fun learnFrom(source: String, messages: List<MemoryMessage>) = true
        override suspend fun forget(source: String) = Unit
    }
    private val card = object : DraftCheck {
        override var sessionId: String? = null
        val started = mutableListOf<Triple<String, Language, String?>>()
        val finished = mutableListOf<String>()
        var stops = 0
        override fun start(draft: String, language: Language, replyingTo: String?) {
            started += Triple(draft, language, replyingTo)
        }
        override fun stop() { stops++ }
        override fun finish(sent: String, timing: DraftTiming?): String? {
            finished += sent
            return sessionId
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
        memory: UserMemory = this.memory,
        startLanguage: suspend () -> String = { "es" },
        answer: suspend () -> String = { """{"reply":"¡Hola!","title":"Greeting","translation":"Hi!"}""" },
    ): ChatViewModel {
        val client = object : ProviderClient {
            override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
                requests += request
                return answer()
            }
            override suspend fun models(key: String): List<String> = emptyList()
        }
        val partner = ChatPartner(
            FakePreferences(), ProviderKeys { "key" }, mapOf(Provider.Gemini to client), { memory },
            dispatcher = main.dispatcher,
        ) { ChatPrompt("chat", "{}") }
        return ChatViewModel(
            store = store, partner = partner, preferences = ChatPreferences(context), memory = { memory },
            startLanguage = startLanguage, drainDeletions = {}, people = { emptyList() },
            translate = { error("Bundled translations must not need another request") }, warmTargets = {},
            appScope = scope, clock = { 10L }, currentLanguage = { null },
        ).also {
            owners.put("chat", it)
            it.draftCheck = card
        }
    }

    private suspend fun seed(id: String = "thread", title: String = "", custom: Boolean = false, writerLast: Boolean = false, store: ChatStore = this.store) {
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
            // A real transaction failure that can be repaired without discarding the database.
            failInserts(name, fromWriter = true)
            assertFalse(failingStore.add(ChatMessage("probe", "thread", true, "Probe", "Probe", at = 3)))
            card.sessionId = "saved-check"
            chat.onDraftChange(TextFieldValue("Do not lose this draft"))
            chat.send()
            chat.onCardState(clean("Do not lose this draft"))
            runCurrent()

            assertEquals("A rejected write must leave the writer's text recoverable", "Do not lose this draft", chat.draft.text)
            assertTrue("The check must not finish as sent before the chat write succeeds", card.finished.isEmpty())
            assertTrue("The partner must not answer an unsaved message", requests.isEmpty())
            assertTrue(chat.sendSaveFailed)
            assertEquals(SendState.SaveFailed(Outgoings.looksGood("Do not lose this draft")), chat.send)
            assertEquals(1, readFresh(name).size)

            chat.showList()
            runCurrent()
            chat.retrySend() // A stale callback from the old page cannot retire its failed draft.
            runCurrent()
            assertTrue(chat.sendSaveFailed)
            assertEquals("Do not lose this draft", chat.draft.text)
            chat.open("thread")
            runCurrent()
            chat.retrySend()
            runCurrent()
            assertTrue(card.finished.isEmpty())
            assertTrue(requests.isEmpty())
            sql(name, "DROP TRIGGER fail_message")
            chat.retrySend()
            runCurrent()
            assertFalse(chat.sendSaveFailed)
            assertFalse(chat.savingMessage)
            assertEquals("", chat.draft.text)
            assertEquals(1, card.started.size)
            assertEquals(listOf("Do not lose this draft"), card.finished)
            assertEquals(1, requests.size)
            val durable = readFresh(name)
            assertEquals("saved-check", durable.single { it.fromWriter }.sessionId)
            assertEquals("Do not lose this draft", durable.single { it.fromWriter }.text)
            assertEquals(chat.thread!!.messages, durable)
        } finally {
            owners.clear()
            runCurrent()
            failingStore.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun aFailedReplySaveKeepsTheWriterWaitingAndRetriesTheSameGeneratedAnswer() = runTest(main.dispatcher) {
        withNamedStore("reply-save-failure") { named ->
            assertTrue(named.create(Conversation("thread", "", "es", 1, 2)))
            assertTrue(named.add(ChatMessage("writer", "thread", true, "Hola", "Hola", at = 3)))
            failInserts("reply-save-failure", fromWriter = false)
            val chat = model(store = named)
            chat.open("thread")
            runCurrent()
            chat.retryReply()
            runCurrent()

            assertTrue(chat.replySaveFailed)
            assertTrue(chat.thread!!.awaitingReply)
            assertEquals(listOf("writer"), chat.thread!!.messages.map { it.id })
            assertEquals(chat.thread!!.messages, readFresh("reply-save-failure"))
            assertEquals(1, requests.size)

            sql("reply-save-failure", "DROP TRIGGER fail_message")
            chat.retryReply()
            runCurrent()
            assertFalse(chat.replySaveFailed)
            assertFalse(chat.thread!!.awaitingReply)
            assertEquals(1, requests.size) // Retry the saved answer, not another provider request.
            assertEquals("Hi!", chat.thread!!.messages.last().translation)
            assertEquals(chat.thread!!.messages, readFresh("reply-save-failure"))
        }
    }

    @Test fun aFailedOpenerSaveStaysInvisibleAndCanBeRetriedWithoutAnotherProviderCall() = runTest(main.dispatcher) {
        withNamedStore("opener-save-failure") { named ->
            assertTrue(named.create(Conversation("thread", "", "es", 1, 2)))
            failInserts("opener-save-failure", fromWriter = false)
            val chat = model(store = named)
            chat.open("thread")
            runCurrent()

            assertTrue(chat.replySaveFailed)
            assertTrue(chat.thread!!.messages.isEmpty())
            assertTrue(readFresh("opener-save-failure").isEmpty())
            assertEquals(1, requests.size)

            sql("opener-save-failure", "DROP TRIGGER fail_message")
            chat.retryReply()
            runCurrent()
            assertFalse(chat.replySaveFailed)
            assertEquals(1, requests.size)
            assertEquals("¡Hola!", chat.thread!!.messages.single().text)
            assertEquals(chat.thread!!.messages, readFresh("opener-save-failure"))
        }
    }

    @Test fun openingAnotherThreadWaitsForTheWriterCommitAndFinishesItsOriginalCheck() = runTest(main.dispatcher) {
        val io = HeldDispatcher(main.dispatcher)
        val named = ChatStore(context, name = null, dispatcher = io)
        try {
            assertTrue(named.create(Conversation("thread", "", "es", 1, 2)))
            assertTrue(named.add(ChatMessage.partner("opener", "thread", "Hola", 2)))
            assertTrue(named.create(Conversation("other", "", "ca", 1, 2)))
            assertTrue(named.add(ChatMessage.partner("other-opener", "other", "Bon dia", 2)))
            val chat = model(store = named)
            chat.open("thread")
            runCurrent()
            card.sessionId = "original-check"
            chat.onDraftChange(TextFieldValue("A committed message"))
            chat.send()
            io.hold = true
            chat.onCardState(clean("A committed message"))
            runCurrent()
            assertTrue(chat.savingMessage)
            assertTrue(io.hasPendingWork)
            assertTrue(card.finished.isEmpty())
            assertEquals("A committed message", chat.draft.text)

            chat.open("other")
            runCurrent()
            assertEquals("thread", chat.thread!!.conversation.id)
            io.release()
            runCurrent()
            assertEquals("other", chat.thread!!.conversation.id)
            assertEquals(listOf("A committed message"), card.finished)
            assertEquals("original-check", named.messages("thread").single { it.fromWriter }.sessionId)
            assertTrue(named.messages("other").none { it.fromWriter })
        } finally {
            io.release()
            owners.clear()
            main.dispatcher.scheduler.runCurrent()
            named.close()
        }
    }

    @Test fun skippedLearningDoesNotAdvanceTheConversationWatermarkAndCanRetry() = runTest(main.dispatcher) {
        seed(writerLast = true)
        var learned = false
        var calls = 0
        val learner = object : UserMemory {
            override suspend fun forPrompt() = ""
            override suspend fun learnFrom(source: String, messages: List<MemoryMessage>): Boolean {
                calls++
                return learned
            }
            override suspend fun forget(source: String) = Unit
        }
        val chat = model(memory = learner)
        chat.open("thread")
        runCurrent()
        chat.leave()
        runCurrent()
        assertEquals(1, calls)
        assertEquals(0, store.conversation("thread")!!.learnedCount)

        learned = true
        chat.leave()
        runCurrent()
        assertEquals(2, calls)
        assertEquals(2, store.conversation("thread")!!.learnedCount)
        chat.leave()
        runCurrent()
        assertEquals(2, calls)
    }

    @Test fun failedCreationRetainsItsIdentityAndPersonaAndBlocksSendingUntilRetrySucceeds() = runTest(main.dispatcher) {
        withNamedStore("create-failure") { named ->
            named.conversations() // Initialize the real schema before installing the fault.
            sql("create-failure", "CREATE TRIGGER fail_metadata BEFORE INSERT ON conversations BEGIN SELECT RAISE(ABORT, 'write failed'); END")
            val chat = model(store = named)
            val id = chat.newConversation("My landlord", "es")
            runCurrent()
            val failed = chat.conversationSaveFailure as ConversationWriteFailure.Create
            assertEquals(id, failed.id)
            assertEquals("My landlord", failed.conversation.persona)
            assertNull(named.conversation(id))
            assertTrue(requests.isEmpty())
            chat.onDraftChange(TextFieldValue("Keep this draft"))
            chat.send()
            assertTrue(card.started.isEmpty())

            sql("create-failure", "DROP TRIGGER fail_metadata")
            chat.retryConversationWrite()
            runCurrent()
            assertNull(chat.conversationSaveFailure)
            assertEquals(id, chat.thread!!.conversation.id)
            assertEquals("My landlord", named.conversation(id)!!.persona)
            assertEquals("Keep this draft", chat.draft.text)
            assertEquals(1, requests.size)
        }
    }

    @Test fun failedRenameKeepsTheDurableTitleAndRetriesTheRequestedName() = runTest(main.dispatcher) {
        withNamedStore("rename-failure") { named ->
            assertTrue(named.create(Conversation("thread", "Old title", "es", 1, 2)))
            assertTrue(named.add(ChatMessage.partner("opener", "thread", "Hola", 2)))
            val chat = model(store = named)
            chat.open("thread")
            runCurrent()
            sql("rename-failure", "CREATE TRIGGER fail_metadata BEFORE UPDATE OF title ON conversations BEGIN SELECT RAISE(ABORT, 'write failed'); END")
            chat.rename("thread", "My new title")
            runCurrent()
            assertEquals("Old title", chat.thread!!.conversation.title)
            assertEquals("Old title", named.conversation("thread")!!.title)
            assertEquals(ConversationWriteFailure.Rename("thread", "My new title"), chat.conversationSaveFailure)

            sql("rename-failure", "DROP TRIGGER fail_metadata")
            chat.retryConversationWrite()
            runCurrent()
            assertNull(chat.conversationSaveFailure)
            assertEquals("My new title", chat.thread!!.conversation.title)
            assertEquals("My new title", named.conversation("thread")!!.title)
        }
    }

    @Test fun failedDeletionKeepsTheOpenReviewAndOnlyStopsItAfterAcknowledgedRetry() = runTest(main.dispatcher) {
        withNamedStore("delete-failure") { named ->
            assertTrue(named.create(Conversation("thread", "", "es", 1, 2)))
            assertTrue(named.add(ChatMessage.partner("opener", "thread", "Hola", 2)))
            val chat = model(store = named)
            chat.open("thread")
            runCurrent()
            chat.onDraftChange(TextFieldValue("Unsent draft"))
            chat.send()
            chat.onCardState(CardState.Failed(CheckFailure.Reason.Offline, null))
            val stops = card.stops
            sql("delete-failure", "CREATE TRIGGER fail_metadata BEFORE DELETE ON conversations BEGIN SELECT RAISE(ABORT, 'write failed'); END")
            chat.delete("thread")
            runCurrent()
            assertEquals(ConversationWriteFailure.Delete("thread"), chat.conversationSaveFailure)
            assertEquals("thread", chat.thread!!.conversation.id)
            assertEquals("Unsent draft", chat.draft.text)
            assertEquals(SendState.Reviewing("Unsent draft"), chat.send)
            assertEquals(stops, card.stops)
            assertEquals(1, readFresh("delete-failure").size)

            sql("delete-failure", "DROP TRIGGER fail_metadata")
            chat.retryConversationWrite()
            runCurrent()
            assertNull(chat.conversationSaveFailure)
            assertNull(chat.thread)
            assertNull(named.conversation("thread"))
            assertEquals(stops + 1, card.stops)
        }
    }

    private suspend fun withNamedStore(name: String, block: suspend (ChatStore) -> Unit) {
        context.deleteDatabase(name)
        val named = ChatStore(context, name, main.dispatcher)
        try {
            block(named)
        } finally {
            owners.clear()
            main.dispatcher.scheduler.runCurrent()
            named.close()
            context.deleteDatabase(name)
        }
    }

    private fun sql(name: String, command: String) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL(command)
        }
    }

    private fun failInserts(name: String, fromWriter: Boolean) = sql(name,
        "CREATE TRIGGER fail_message BEFORE INSERT ON messages WHEN NEW.from_writer = ${if (fromWriter) 1 else 0} " +
            "BEGIN SELECT RAISE(ABORT, 'forced write failure'); END")

    private suspend fun readFresh(name: String): List<ChatMessage> {
        val reopened = ChatStore(context, name, main.dispatcher)
        return try { reopened.messages("thread") } finally { reopened.close() }
    }

    private suspend fun withHeldStore(block: suspend (ChatStore, HeldDispatcher) -> Unit) {
        val io = HeldDispatcher(main.dispatcher)
        val held = ChatStore(context, name = null, dispatcher = io)
        try {
            block(held, io)
        } finally {
            io.release()
            owners.clear()
            main.dispatcher.scheduler.runCurrent()
            held.close()
        }
    }

    private class HeldDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var hold = false
        private val pending = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        val hasPendingWork get() = pending.isNotEmpty()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (hold) pending.addLast(context to block) else delegate.dispatch(context, block)
        }
        /** Execute SQLite without resuming its Main acknowledgement yet. */
        fun runFirstNow() { pending.removeFirst().second.run() }
        fun runLastNow() { pending.removeLast().second.run() }
        fun release() {
            hold = false
            while (pending.isNotEmpty()) {
                val (context, block) = pending.removeFirst()
                delegate.dispatch(context, block)
            }
        }
    }

    @Test
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

    @Test fun anOlderCreationCannotPruneTheNewerBlankThreadOrItsDraft() = runTest(main.dispatcher) {
        val language = CompletableDeferred<String>()
        val chat = model(startLanguage = { language.await() })
        val old = chat.newConversation()
        runCurrent()
        val selected = chat.newConversation(language = "es")
        runCurrent()
        chat.onDraftChange(TextFieldValue("A draft in the newer conversation"))
        language.complete("es")
        runCurrent()
        assertNotNull(store.conversation(old))
        assertNotNull("Stale creation must not prune the newer empty conversation", store.conversation(selected))
        assertEquals(selected, chat.thread!!.conversation.id)
        assertEquals("A draft in the newer conversation", chat.draft.text)
        assertEquals(1, requests.size)
    }

    @Test fun olderOpenReadCannotReplaceTheNewerSelectionOrDraft() = runTest(main.dispatcher) {
        withHeldStore { held, io ->
            seed(id = "first", store = held)
            seed(id = "second", store = held)
            val chat = model(store = held)
            io.hold = true
            chat.open("first")
            runCurrent()
            chat.open("second")
            runCurrent()
            io.runLastNow()
            runCurrent()
            assertEquals("second", chat.thread!!.conversation.id)
            chat.onDraftChange(TextFieldValue("Keep this draft"))
            io.release()
            runCurrent()
            assertEquals("second", chat.thread!!.conversation.id)
            assertEquals("Keep this draft", chat.draft.text)
        }
    }

    @Test fun anObsoleteMissingReadCannotNavigateBackFromTheNewSelection() = runTest(main.dispatcher) {
        withHeldStore { held, io ->
            seed(store = held)
            val chat = model(store = held)
            var missing = false
            io.hold = true
            chat.open("deleted") { missing = true }
            runCurrent()
            chat.open("thread")
            runCurrent()
            io.release()
            runCurrent()
            assertFalse(missing)
            assertEquals("thread", chat.thread!!.conversation.id)
        }
    }

    @Test fun aReplyQueuedForStorageIsRevokedByLeavingAndReopeningTheSameWriterTurn() = runTest(main.dispatcher) {
        withHeldStore { held, io ->
            seed(writerLast = true, store = held)
            seed(id = "other", store = held)
            val oldAnswer = CompletableDeferred<String>()
            val newAnswer = CompletableDeferred<String>()
            val chat = model(store = held, answer = { if (requests.size == 1) oldAnswer.await() else newAnswer.await() })
            chat.open("thread")
            runCurrent()
            chat.retryReply()
            runCurrent()
            io.hold = true
            oldAnswer.complete("""{"reply":"Obsolete answer","title":"Obsolete title"}""")
            runCurrent()
            assertTrue(io.hasPendingWork)
            chat.open("other")
            runCurrent()
            chat.open("thread")
            runCurrent()
            io.release()
            runCurrent()
            assertTrue(held.messages("thread").none { it.text == "Obsolete answer" })
            assertEquals("", held.conversation("thread")!!.title)
            chat.retryReply()
            runCurrent()
            assertTrue(chat.replying)
            assertEquals(2, requests.size)
            newAnswer.complete("""{"reply":"Current answer","title":"Current title"}""")
            runCurrent()
            assertEquals("Current answer", chat.thread!!.messages.last().text)
            assertEquals(chat.thread!!.messages, held.messages("thread"))
            assertFalse(chat.replying)
            assertFalse(chat.replySaveFailed)
        }
    }

    @Test fun anOpenerQueuedForStorageCannotAppendAfterTheWriterSpeaks() = runTest(main.dispatcher) {
        withHeldStore { held, io ->
            val opener = CompletableDeferred<String>()
            val reply = CompletableDeferred<String>()
            val chat = model(store = held, answer = { if (requests.size == 1) opener.await() else reply.await() })
            val id = chat.newConversation(language = "es")
            runCurrent()
            io.hold = true
            opener.complete("""{"reply":"Late opener"}""")
            runCurrent()
            chat.onDraftChange(TextFieldValue("I spoke first"))
            chat.send()
            chat.onCardState(clean("I spoke first"))
            runCurrent()
            io.release()
            runCurrent()
            assertEquals(listOf("I spoke first"), held.messages(id).map { it.text })
            assertTrue(chat.replying)
            assertEquals(2, requests.size)
            reply.complete("""{"reply":"A reply to your message"}""")
            runCurrent()
            assertEquals(listOf("I spoke first", "A reply to your message"), chat.thread!!.messages.map { it.text })
        }
    }

    @Test fun aReplyCommittedBeforeItsMainAcknowledgementRemainsInTheNextSendContext() = runTest(main.dispatcher) {
        withHeldStore { held, io ->
            seed(writerLast = true, store = held)
            val first = CompletableDeferred<String>()
            val second = CompletableDeferred<String>()
            val chat = model(store = held, answer = { if (requests.size == 1) first.await() else second.await() })
            chat.open("thread")
            runCurrent()
            chat.retryReply()
            runCurrent()
            io.hold = true
            first.complete("""{"reply":"Already committed reply"}""")
            runCurrent()
            io.runFirstNow() // SQLite commits; its Main continuation has not run.
            assertTrue(chat.thread!!.messages.none { it.text == "Already committed reply" })
            chat.onDraftChange(TextFieldValue("The next writer message"))
            chat.send()
            chat.onCardState(clean("The next writer message"))
            runCurrent()
            io.release()
            runCurrent()
            assertEquals(2, requests.size)
            assertTrue(requests.last().user.contains("Already committed reply"))
            assertTrue(requests.last().user.contains("The next writer message"))
            assertEquals(listOf("¿Qué tal?", "Bien", "Already committed reply", "The next writer message"),
                chat.thread!!.messages.map { it.text })
            second.complete("""{"reply":"The final answer"}""")
            runCurrent()
            assertEquals(held.messages("thread"), chat.thread!!.messages)
            assertEquals(5, chat.thread!!.messages.size)
        }
    }

    @Test fun unavailableListPreservesPreviousResultsAndCanRetry() = runTest(main.dispatcher) {
        withNamedStore("list-read-failure") { named ->
            seed(writerLast = true, store = named)
            val chat = model(store = named)
            chat.showList()
            runCurrent()
            val previous = chat.conversations
            assertEquals(1, previous.size)
            sql("list-read-failure", "ALTER TABLE conversations RENAME TO unavailable_conversations")
            chat.showList()
            runCurrent()
            assertTrue(chat.loadFailed)
            assertEquals(previous, chat.conversations)
            sql("list-read-failure", "ALTER TABLE unavailable_conversations RENAME TO conversations")
            chat.retryLoad()
            runCurrent()
            assertFalse(chat.loadFailed)
            assertEquals(previous, chat.conversations)
        }
    }

    @Test fun unavailableThreadIsNotReportedMissingAndCanRetryWithoutDiscardingPreviousData() = runTest(main.dispatcher) {
        withNamedStore("thread-read-failure") { named ->
            seed(store = named)
            seed(id = "other", store = named)
            val chat = model(store = named)
            chat.open("thread")
            runCurrent()
            chat.onDraftChange(TextFieldValue("Keep the old draft until the next thread loads"))
            sql("thread-read-failure", "ALTER TABLE conversations RENAME TO unavailable_conversations")
            var missing = false
            chat.open("other") { missing = true }
            runCurrent()
            assertFalse(missing)
            assertTrue(chat.loadFailed)
            assertEquals("thread", chat.thread!!.conversation.id)
            assertEquals("Keep the old draft until the next thread loads", chat.draft.text)
            sql("thread-read-failure", "ALTER TABLE unavailable_conversations RENAME TO conversations")
            chat.retryLoad()
            runCurrent()
            assertFalse(chat.loadFailed)
            assertFalse(missing)
            assertEquals("other", chat.thread!!.conversation.id)
        }
    }


    @Test fun leavingForTheListDuringAWriterCommitDoesNotResurrectTheSentDraftOnReturn() = runTest(main.dispatcher) {
        withHeldStore { held, io ->
            seed(store = held)
            val chat = model(store = held)
            chat.open("thread")
            runCurrent()
            chat.onDraftChange(TextFieldValue("Commit before leaving"))
            chat.send()
            io.hold = true
            chat.onCardState(clean("Commit before leaving"))
            runCurrent()
            chat.showList()
            runCurrent()
            io.release()
            runCurrent()
            chat.open("thread")
            runCurrent()
            assertEquals(SendState.Composing, chat.send)
            assertEquals("", chat.draft.text)
            assertEquals(listOf("Commit before leaving"), card.finished)
            assertEquals(1, chat.thread!!.messages.count { it.fromWriter })
            assertEquals("Commit before leaving", chat.thread!!.messages.last().text)
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun aBackgroundListRefreshCannotStealCurrentRouteResolution() = runTest(main.dispatcher) {
        withHeldStore { held, io ->
            seed(writerLast = true, store = held)
            val chat = model(store = held)
            chat.open("thread")
            runCurrent()
            io.hold = true
            var resolved: String? = "unresolved"
            chat.openCurrent(null) { resolved = it }
            runCurrent()
            chat.rename("thread", "Renamed while resolving")
            runCurrent()
            io.runLastNow() // Rename commits, then its list refresh supersedes list publication.
            runCurrent()
            io.runFirstNow() // The current-route query still owns its navigation result.
            runCurrent()
            assertEquals("thread", resolved)
            io.release()
            runCurrent()
            assertEquals("thread", chat.thread!!.conversation.id)
            assertEquals("Renamed while resolving", chat.thread!!.conversation.title)
        }
    }


    @Test fun anOlderManualRenameCannotOverwriteTheLatestCommandAfterIoDispatch() = runTest(main.dispatcher) {
        withHeldStore { held, io ->
            seed(store = held)
            val chat = model(store = held)
            chat.open("thread")
            runCurrent()
            io.hold = true
            chat.rename("thread", "Older title")
            runCurrent()
            chat.rename("thread", "Latest title")
            runCurrent()
            io.runLastNow()
            runCurrent()
            assertEquals("Latest title", chat.thread!!.conversation.title)
            io.release()
            runCurrent()
            assertEquals("Latest title", held.conversation("thread")!!.title)
            assertEquals("Latest title", chat.thread!!.conversation.title)
            assertNull(chat.conversationSaveFailure)
        }
    }


    @Test fun rejoiningAFailedPendingCreationKeepsItsExactRetryCommand() = runTest(main.dispatcher) {
        withNamedStore("rejoined-create-failure") { named ->
            seed(id = "existing", writerLast = true, store = named)
            sql("rejoined-create-failure", "CREATE TRIGGER fail_create BEFORE INSERT ON conversations " +
                "BEGIN SELECT RAISE(ABORT, 'forced create failure'); END")
            val language = CompletableDeferred<String>()
            val chat = model(store = named, startLanguage = { language.await() })
            val id = chat.newConversation(persona = "My teacher")
            runCurrent()
            chat.open("existing")
            runCurrent()
            chat.open(id)
            runCurrent()
            language.complete("es")
            runCurrent()
            val failure = chat.conversationSaveFailure as ConversationWriteFailure.Create
            assertEquals(id, failure.id)
            assertEquals("My teacher", failure.conversation.persona)
            assertNull(chat.creatingConversationId)
            sql("rejoined-create-failure", "DROP TRIGGER fail_create")
            chat.retryConversationWrite()
            runCurrent()
            assertEquals(id, chat.thread!!.conversation.id)
            assertEquals("My teacher", named.conversation(id)!!.persona)
            assertNull(chat.conversationSaveFailure)
        }
    }

    @Test fun unavailableCurrentRouteDoesNotResolveAsAnEmptyConversationList() = runTest(main.dispatcher) {
        withNamedStore("current-read-failure") { named ->
            seed(writerLast = true, store = named)
            val chat = model(store = named)
            sql("current-read-failure", "ALTER TABLE conversations RENAME TO unavailable_conversations")
            var resolved: String? = "unresolved"
            chat.openCurrent(null) { resolved = it }
            runCurrent()
            assertEquals("unresolved", resolved)
            assertTrue(chat.loadFailed)
            sql("current-read-failure", "ALTER TABLE unavailable_conversations RENAME TO conversations")
            chat.retryLoad()
            runCurrent()
            assertEquals("thread", resolved)
            assertFalse(chat.loadFailed)
            assertEquals("thread", chat.thread!!.conversation.id)
        }
    }


    @Test fun anOlderRenameSuccessCannotClearTheNewConversationFailure() = runTest(main.dispatcher) {
        val name = "metadata-failure-owner"
        context.deleteDatabase(name)
        val io = HeldDispatcher(main.dispatcher)
        val named = ChatStore(context, name, io)
        try {
            seed(writerLast = true, store = named)
            val chat = model(store = named)
            chat.open("thread")
            runCurrent()
            io.hold = true
            chat.rename("thread", "A durable older rename")
            runCurrent()
            sql(name, "CREATE TRIGGER fail_create BEFORE INSERT ON conversations " +
                "BEGIN SELECT RAISE(ABORT, 'forced create failure'); END")
            val failedId = chat.newConversation(language = "es")
            runCurrent()
            io.runLastNow() // New creation fails before the older rename commits.
            runCurrent()
            assertEquals(failedId, (chat.conversationSaveFailure as ConversationWriteFailure.Create).id)
            io.release()
            runCurrent()
            assertEquals("A durable older rename", named.conversation("thread")!!.title)
            assertEquals(failedId, (chat.conversationSaveFailure as ConversationWriteFailure.Create).id)
            sql(name, "DROP TRIGGER fail_create")
            chat.retryConversationWrite()
            runCurrent()
            assertNotNull(named.conversation(failedId))
            assertNull(chat.conversationSaveFailure)
        } finally {
            io.release()
            owners.clear()
            main.dispatcher.scheduler.runCurrent()
            named.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun anOlderMetadataFailureCannotReplaceTheNewConversationSuccess() = runTest(main.dispatcher) {
        withHeldStore { held, io ->
            val chat = model(store = held)
            io.hold = true
            chat.rename("missing", "An obsolete failed command")
            runCurrent()
            val id = chat.newConversation(language = "es")
            runCurrent()
            io.runLastNow() // New creation commits before the older failing rename.
            runCurrent()
            assertNull(chat.conversationSaveFailure)
            io.release()
            runCurrent()
            assertEquals(id, chat.thread!!.conversation.id)
            assertNotNull(held.conversation(id))
            assertNull(chat.conversationSaveFailure)
        }
    }

    @Test fun aReplySupersededByAnExternalWriterRefreshesItsTailBeforeRetry() = runTest(main.dispatcher) {
        seed(writerLast = true)
        val first = CompletableDeferred<String>()
        val second = CompletableDeferred<String>()
        val chat = model(answer = { if (requests.size == 1) first.await() else second.await() })
        chat.open("thread")
        runCurrent()
        chat.retryReply()
        runCurrent()
        chat.onDraftChange(TextFieldValue("Keep this unsent draft"))
        val external = ChatMessage("external-writer", "thread", true, "An external send", "An external send", at = 4)
        assertTrue(store.add(external))
        first.complete("""{"reply":"Answer to the obsolete tail"}""")
        runCurrent()
        assertFalse(chat.replying)
        assertEquals(external, chat.thread!!.messages.last())
        assertEquals(store.messages("thread"), chat.thread!!.messages)
        assertEquals("Keep this unsent draft", chat.draft.text)
        chat.retryReply()
        runCurrent()
        assertTrue(requests.last().user.contains("An external send"))
        second.complete("""{"reply":"Answer to the current tail"}""")
        runCurrent()
        assertEquals("Answer to the current tail", chat.thread!!.messages.last().text)
        assertEquals("Keep this unsent draft", chat.draft.text)
        assertEquals(store.messages("thread"), chat.thread!!.messages)
        assertTrue(chat.thread!!.messages.none { it.text == "Answer to the obsolete tail" })
    }

}
