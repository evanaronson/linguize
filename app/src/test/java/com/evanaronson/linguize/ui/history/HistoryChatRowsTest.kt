package com.evanaronson.linguize.ui.history

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.core.chat.SentCheck
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.history.HistoryCursor
import com.evanaronson.linguize.history.HistoryPage
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.Outcome
import com.evanaronson.linguize.history.SessionSummary
import com.evanaronson.linguize.history.SqliteHistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lab Chat in History: a chat message is one row like any other, labelled with its conversation,
 * and deleting that row deletes only its History record, never the conversation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class HistoryChatRowsTest {
    private val app = RuntimeEnvironment.getApplication() as App
    private val chat = ChatStore(app, NAME)
    private val sessions = MutableStateFlow(listOf(
        summary("chat1", 100, Origin.Chat),
        summary("wa1", 90, Origin.Menu, "com.whatsapp"),
        summary("chat2", 80, Origin.Chat),
        summary("chat3", 70, Origin.Chat),
        summary("wa2", 60, Origin.Menu, "com.whatsapp"),
    ))
    private val revisions = MutableStateFlow(0L)
    private val deleted = CopyOnWriteArrayList<String>()
    private val history = object : HistoryStore by SqliteHistoryStore(app, "unused") {
        override fun revisions() = revisions
        override suspend fun page(before: HistoryCursor?, limit: Int) = HistoryPage(sessions.value.take(limit), null)
        override suspend fun delete(id: String): Boolean {
            deleted += id
            sessions.update { list -> list.filterNot { it.id == id } }
            revisions.update { it + 1 }
            return true
        }
    }
    private val models = ViewModelStore()
    private val model by lazy {
        ViewModelProvider(models, viewModelFactory {
            initializer { HistoryViewModel(app, history, threads = { chat.threads(it) }) }
        })[HistoryViewModel::class.java]
    }

    private fun summary(id: String, at: Long, origin: Origin, app: String? = null) =
        SessionSummary(id, at, origin, app, "text $id", Outcome.Applied, 1, 0, 1)

    @After fun close() {
        models.clear()
        chat.close()
        app.deleteDatabase(NAME)
    }

    private fun seedConversation() = runBlocking {
        assertTrue(chat.create(Conversation("c1", "Weekend plans", "es", createdAt = 1, updatedAt = 1)))
        listOf("chat1", "chat2", "chat3").forEachIndexed { index, session ->
            assertTrue(chat.add(ChatMessage("m$index", "c1", fromWriter = true, raw = "hola $index", text = "hola $index",
                check = SentCheck.LooksGood, at = 10L + index, sessionId = session)))
        }
    }

    @Test fun everyChatMessageIsItsOwnRowAmongTheOthers() {
        seedConversation()
        model.showHistory(25)
        waitFor { model.historyList.value.rows.size == 5 && model.historyList.value.rows.first().chat != null }
        val rows = model.historyList.value.rows
        assertEquals(listOf("chat1", "wa1", "chat2", "chat3", "wa2"), rows.map { it.key })
        assertEquals(listOf("c1", null, "c1", "c1", null), rows.map { it.chat?.conversationId })
        assertEquals("Chat · Weekend plans", rows[2].sourceLabel(app.resources))
    }

    @Test fun deletingAChatRowLeavesTheConversation() {
        seedConversation()
        model.showHistory(25)
        waitFor { model.historyList.value.rows.size == 5 }
        model.delete("chat2")
        waitFor { model.historyList.value.rows.none { it.summary.id == "chat2" } }
        model.commitDelete("chat2")
        waitFor { deleted == listOf("chat2") && model.historyList.value.rows.size == 4 }
        runBlocking {
            assertNotNull(chat.conversation("c1"))
            assertEquals(listOf("m0", "m1", "m2"), chat.messages("c1").map { it.id })
            // The chat is untouched: the message keeps its (now unused) note of the deleted record.
            assertEquals("chat2", chat.messages("c1")[1].sessionId)
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for history")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }

    private companion object {
        const val NAME = "lab_history_flat_chat_test"
    }
}
