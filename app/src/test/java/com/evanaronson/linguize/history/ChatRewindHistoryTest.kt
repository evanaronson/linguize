package com.evanaronson.linguize.history

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.ChatDeletions
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.VerdictChange
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.core.chat.SentCheck
import com.evanaronson.linguize.core.interpret
import com.evanaronson.linguize.core.time.TimeRecord
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.data.time.TimeStore
import com.evanaronson.linguize.history.corpus.CheckCorpusSource
import com.evanaronson.linguize.history.patterns.PatternAnalyser
import com.evanaronson.linguize.history.patterns.patternEvidence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lab Chat, editing a sent message: the rewind's cleanup deletes each removed message's History
 * record and time record with History's own delete, so everything derived from History (the
 * corpus behind Words and Conjugations, and Patterns) drops them on its next read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatRewindHistoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val history = SqliteHistoryStore(context, NAME)
    private val chats = ChatStore(context, name = null)
    private val time = TimeStore(context, CoroutineScope(Dispatchers.Unconfined), name = null)
    private var now = 1_000L
    private val environment = object : HistoryEnvironment {
        override val enabled = true
        override val deviceId = "device"
        override val appVersion = "1.0"
    }
    private val settings = SessionSettings("English", "moderate", "both", "gemini", "m", "p1")
    private val forgotten = mutableListOf<String>()
    private val cleanup = ChatDeletions(chats,
        forgetMemory = { forgotten += it },
        deleteHistory = { history.delete(it) },
        deleteTiming = { time.deleteAcknowledged(it) },
    )

    @After fun close() {
        chats.close()
        time.close()
        context.deleteDatabase(NAME)
    }

    private val wrong = "Ayer yo fue a la playa"
    private val verdict = Verdict(
        status = Verdict.Status.Ok, language = "Spanish", hasErrors = true, corrected = "Ayer yo fui a la playa",
        fixes = listOf(VerdictChange("fue", "fui", "First person")),
    )

    /** Saves [text] in History as a chat message, as the chat's card does, with its time record. */
    private suspend fun send(text: String, result: CheckResult, accept: Boolean): String {
        val check = CheckHistory(history, environment, CoroutineScope(Dispatchers.Unconfined), clock = { now++ })
        val session = check.open(text, Opening(Origin.Chat, null, "es", settings))!!
        check.succeeded(emptyList(), "{}", result)
        val revision = (result as? CheckResult.Reviewed)?.revision?.let { if (accept) it.acceptAll(EditKind.Fix) else it }
        if (accept) revision?.let { check.changed(it) }
        check.close(revision?.takeIf { it.acceptedCount > 0 }?.workingText ?: text)
        check.flush()
        assertTrue(time.save(TimeRecord(session.id, "chat", "es", 1_000, 500, 5, 1, 1, now)))
        return session.id
    }

    @Test fun rewindingDeletesEachRemovedMessagesHistoryAndTimeRecordAndDerivedViewsDropThem() = runBlocking<Unit> {
        val kept = send("Hola, ¿qué tal?", interpret("Hola, ¿qué tal?", Verdict(Verdict.Status.Ok, language = "Spanish")), false)
        val edited = send(wrong, interpret(wrong, verdict), accept = true)
        val later = send("Mañana voy al cine", interpret("Mañana voy al cine", Verdict(Verdict.Status.Ok, language = "Spanish")), false)
        chats.create(Conversation("c1", "Plans", "es", 1, 1))
        chats.add(ChatMessage.partner("p0", "c1", "¿Qué tal?", 2))
        chats.add(ChatMessage("w1", "c1", true, "Hola, ¿qué tal?", "Hola, ¿qué tal?", check = SentCheck.LooksGood, at = 3, sessionId = kept))
        chats.add(ChatMessage.partner("p1", "c1", "Bien", 4))
        chats.add(ChatMessage("w2", "c1", true, wrong, "Ayer yo fui a la playa", check = SentCheck.Corrected, at = 5, sessionId = edited))
        chats.add(ChatMessage.partner("p2", "c1", "¡Qué bien!", 6))
        chats.add(ChatMessage("w3", "c1", true, "Mañana voy al cine", "Mañana voy al cine", check = SentCheck.LooksGood, at = 7, sessionId = later))
        assertEquals(3, CheckCorpusSource(history).utterances().size)
        assertTrue(PatternAnalyser.analyse(history.patternEvidence()!!).let { r -> r.patterns.flatMap { it.examples } + r.unpatterned }
            .any { it.from == "fue" })
        val revisionBefore = history.revisions().first()

        val left = chats.rewind("c1", "w2")!!
        assertTrue(cleanup.drain())

        assertEquals(listOf("p0", "w1", "p1"), left.messages.map { it.id })
        // Each removed writer message's History record is gone; the kept one stays.
        assertEquals(SessionDetailResult.Missing, history.detail(edited))
        assertEquals(SessionDetailResult.Missing, history.detail(later))
        assertTrue(history.detail(kept) is SessionDetailResult.Found)
        assertEquals(listOf(kept), history.recent(10).first().map { it.id })
        // Their time records too.
        assertEquals(listOf(kept), time.all().map { it.sessionId })
        // History's change signal moved, and what derives from it drops them.
        assertNotEquals(revisionBefore, history.revisions().first())
        assertEquals(listOf("Hola, ¿qué tal?"), CheckCorpusSource(history).utterances().map { it.text })
        assertTrue(PatternAnalyser.analyse(history.patternEvidence()!!).let { r -> r.patterns.flatMap { it.examples } + r.unpatterned }
            .none { it.from == "fue" })
        assertEquals(listOf("chat:c1"), forgotten)
        assertTrue(chats.pendingDeletions().entries.isEmpty())
    }

    companion object {
        private const val NAME = "chat-rewind-history-test"
    }
}
