package com.evanaronson.linguize.ui.chat

import android.app.Application
import android.content.Context
import android.os.Looper
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
import com.evanaronson.linguize.ui.card.CardState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Lab, Time spent: the chat measures writing up to Send and hands it to the card that saves the message. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatTimingTest {
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
    private var now = 1_000_000L
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
    )

    /** The card, faked: what it was given when the message went. */
    private val card = object : DraftCheck {
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
        store.close()
    }

    private fun type(text: String) = chat.onDraftChange(TextFieldValue(text))

    private fun openConversation() {
        chat.draftCheck = card
        chat.newConversation()
        val until = System.currentTimeMillis() + 5_000
        while (chat.thread == null && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
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
        chat.keepEditing()
        now += 2_000
        type("Hola, ¿qué tal?")
        chat.send()
        chat.onCardState(CardState.Failed(CheckFailure.Reason.Offline, null))
        card.reviewSoFar = 3_000
        assertEquals(SendState.Reviewing("Hola, ¿qué tal?"), chat.send)
        chat.sendReviewed(Revision("Hola, ¿qué tal?", emptyList()))

        val (sent, timing) = card.finished!!
        assertEquals("Hola, ¿qué tal?", sent)
        assertEquals(7_000L, timing!!.writingMs)
        // The card's own review (3 s) is added by the card; the earlier opening is carried here.
        assertEquals(8_000L, timing.earlierReviewMs)
        assertEquals(chat.thread!!.messages.last().id, timing.messageId)
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
        assertEquals(3_000L, card.finished!!.second!!.writingMs)
    }
}
