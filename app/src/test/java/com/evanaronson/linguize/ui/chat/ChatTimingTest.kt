package com.evanaronson.linguize.ui.chat

import android.app.Application
import android.content.Context
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.ChatPartner
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.chat.SendState
import com.evanaronson.linguize.core.memory.MemoryMessage
import com.evanaronson.linguize.core.memory.UserMemory
import com.evanaronson.linguize.core.time.DraftTiming
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.data.chat.ChatPreferences
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.chat.ChatPrompt
import com.evanaronson.linguize.testing.MainDispatcherRule
import com.evanaronson.linguize.ui.card.CardState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Lab, Time spent: the chat measures writing up to Send and hands it to the card that saves the message. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ChatTimingTest {
    @get:Rule val main = MainDispatcherRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ChatStore(context, name = null, dispatcher = main.dispatcher)
    private val scope = CoroutineScope(SupervisorJob() + main.dispatcher)
    private val memory = object : UserMemory {
        override suspend fun forPrompt() = ""
        override suspend fun learnFrom(source: String, messages: List<MemoryMessage>) = true
        override suspend fun forget(source: String) = Unit
    }
    private val partner = ChatPartner(FakePreferences(), ProviderKeys { null }, emptyMap(), { memory }, dispatcher = main.dispatcher) {
        ChatPrompt.load { File("src/main/assets/$it").readText() }
    }
    private var now = 1_000_000L
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
        clock = { now },
    )

    /** The card, faked: what it was given when the message went. */
    private val card = object : DraftCheck {
        override val sessionId = "session"
        var reviewSoFar = 0L
        var finished: Pair<String, DraftTiming?>? = null
        var stops = 0
        override fun start(draft: String, language: Language, replyingTo: String?) = Unit
        override fun stop() { stops++ }
        override fun finish(sent: String, timing: DraftTiming?): String? {
            finished = sent to timing
            return "session"
        }
        override fun reviewMs() = reviewSoFar
    }

    @After fun close() {
        scope.cancel()
        main.dispatcher.scheduler.runCurrent()
        store.close()
    }

    private fun type(text: String) = chat.onDraftChange(TextFieldValue(text))

    private fun openConversation() {
        chat.draftCheck = card
        chat.newConversation()
        main.dispatcher.scheduler.runCurrent()
    }

    @Test fun sendingRecordsWritingAndTheReviewOfEveryOpeningOfTheCard() {
        openConversation()
        now += 60_000 // reading the opener isn't writing
        type("H")
        now += 4_000
        type("Hola qué tal")
        now += 1_000
        chat.send()
        // The check fails; the card shows why, and the writer goes back to the draft.
        chat.onCardState(CardState.Failed(CheckFailure.Reason.Offline, null))
        now += 30_000
        card.reviewSoFar = 8_000
        chat.editDraft()
        now += 2_000
        type("Hola, ¿qué tal?")
        chat.send()
        chat.onCardState(CardState.Failed(CheckFailure.Reason.Offline, null))
        card.reviewSoFar = 3_000
        assertEquals(SendState.Reviewing("Hola, ¿qué tal?"), chat.send)
        chat.sendReviewed(Revision("Hola, ¿qué tal?", emptyList()))
        main.dispatcher.scheduler.runCurrent()

        val (sent, timing) = card.finished!!
        assertEquals("Hola, ¿qué tal?", sent)
        assertEquals(7_000L, timing!!.writingMs)
        // The card's own review (3 s) is added by the card; the earlier opening is carried here.
        assertEquals(8_000L, timing.earlierReviewMs)
        assertEquals(chat.thread!!.messages.last().id, timing.messageId)
    }

    /** Lab, Chat corrections: Edit message (and system Back) is a way back, not a way to lose the draft. */
    @Test fun editMessageReturnsToTheDraftAsItWasWithTheKeyboard() {
        openConversation()
        type("Hola qué tal")
        chat.send()
        chat.onCardState(CardState.Failed(CheckFailure.Reason.Offline, null))
        assertEquals(SendState.Reviewing("Hola qué tal"), chat.send)
        val stopsBefore = card.stops
        chat.editDraft()
        assertEquals(SendState.Composing, chat.send)
        assertEquals("Hola qué tal", chat.draft.text)
        assertEquals(stopsBefore + 1, card.stops)
        assertEquals(true, chat.focusDraft)
        chat.draftFocused()
        assertEquals(false, chat.focusDraft)
        // Nothing to go back from: a second Back does nothing here.
        chat.editDraft()
        assertEquals(false, chat.focusDraft)
    }

    @Test fun timeOffScreenIsntWriting() {
        openConversation()
        type("H")
        now += 2_000
        chat.visible(false)
        now += 3_600_000
        chat.visible(true)
        now += 1_000
        type("Hola")
        chat.send()
        chat.onCardState(CardState.Failed(CheckFailure.Reason.Offline, null))
        chat.sendReviewed(Revision("Hola", emptyList()))
        main.dispatcher.scheduler.runCurrent()
        assertEquals(3_000L, card.finished!!.second!!.writingMs)
    }
}
