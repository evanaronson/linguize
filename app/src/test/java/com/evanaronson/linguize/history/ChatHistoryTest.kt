package com.evanaronson.linguize.history

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.VerdictChange
import com.evanaronson.linguize.core.corpus.Span
import com.evanaronson.linguize.core.interpret
import com.evanaronson.linguize.history.corpus.CheckCorpusSource
import com.evanaronson.linguize.history.patterns.PatternAnalyser
import com.evanaronson.linguize.history.patterns.patternEvidence
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lab Chat: a chat message is a History record (Origin.Chat), saved through the same path as a
 * check put back in another app, and read by the corpus and Patterns like one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatHistoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = SqliteHistoryStore(context, NAME)
    private var enabled = true
    private var now = 1_000L
    private val environment = object : HistoryEnvironment {
        override val enabled get() = this@ChatHistoryTest.enabled
        override val deviceId = "device"
        override val appVersion = "1.0"
    }
    private val settings = SessionSettings("English", "moderate", "both", "gemini", "m", "p1")

    @After
    fun deleteDatabase() {
        context.deleteDatabase(NAME)
    }

    private val wrong = "Ayer yo fue a la playa"
    private val verdict = Verdict(
        status = Verdict.Status.Ok,
        language = "Spanish",
        hasErrors = true,
        corrected = "Ayer yo fui a la playa",
        fixes = listOf(VerdictChange("fue", "fui", "First person")),
        moreNatural = true,
        natural = "Ayer fui a la playa",
        naturalChanges = listOf(VerdictChange("yo fui", "fui", "Natural")),
    )

    /** Checks [text] as a card would, then closes it as sent: [accept] says which kinds were accepted. */
    private suspend fun send(text: String, origin: Origin, result: CheckResult, accept: Set<EditKind> = emptySet()): String? {
        val history = CheckHistory(store, environment, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            clock = { now++ })
        val session = history.open(text, Opening(origin, null, "es", settings))
        history.succeeded(emptyList(), "{}", result)
        val reviewed = result as? CheckResult.Reviewed
        var revision = reviewed?.revision
        accept.forEach { kind -> revision = revision?.acceptAll(kind) }
        revision?.let { if (it != reviewed?.revision) history.changed(it) }
        history.close(revision?.takeIf { it.acceptedCount > 0 }?.workingText ?: text)
        history.flush()
        return session?.id
    }

    private fun clean(text: String) = interpret(text, Verdict(Verdict.Status.Ok, language = "Spanish"))

    @Test fun theChatOriginIsATokenThatIsRecordedAndKeepsUnchangedText() {
        assertEquals(Origin.Chat, Origin.tokens.decode("chat"))
        assertEquals("chat", Origin.Chat.token)
        assertTrue(Origin.Chat.recorded)
        assertEquals(listOf(Origin.Chat), Origin.entries.filter { it.keepsUnchanged })
        // The version 4 clean-up deletes only origins that aren't recorded: never chat.
        val cleanup = HistorySchema.upgrade(3, HistorySchema.SESSION_COLUMNS.toSet())!!.filter { it.startsWith("DELETE") }
        assertTrue(cleanup.isNotEmpty())
        assertTrue(cleanup.none { it.contains("'chat'") })
    }

    @Test fun aCleanChatMessageIsSavedButAnUnchangedMenuCheckStaysHidden() = runBlocking<Unit> {
        val chat = send("Hola, ¿qué tal?", Origin.Chat, clean("Hola, ¿qué tal?"))!!
        val menu = send("Hola, ¿qué tal?", Origin.Menu, clean("Hola, ¿qué tal?"))
        val detail = (store.detail(chat) as SessionDetailResult.Found).detail
        assertEquals(Origin.Chat, detail.session.origin)
        assertEquals(Outcome.Applied, detail.session.outcome)
        assertEquals("Hola, ¿qué tal?", detail.session.finalText)
        assertEquals(SessionDetailResult.Missing, menu?.let { store.detail(it) } ?: SessionDetailResult.Missing)
        assertEquals(listOf(chat), store.recent(10).first().map { it.id })
        assertEquals(1, store.count().first())
    }

    @Test fun fixesAcceptedAndSentAsWrittenAreBothRecordedWithTheirDecisions() = runBlocking<Unit> {
        val result = interpret(wrong, verdict)
        val corrected = send(wrong, Origin.Chat, result, accept = setOf(EditKind.Fix))!!
        val asWritten = send(wrong, Origin.Chat, result)!!
        val fixed = (store.detail(corrected) as SessionDetailResult.Found).detail
        assertEquals("Ayer yo fui a la playa", fixed.session.finalText)
        assertEquals(Decision.Accepted, fixed.suggestions.single { it.kind == EditKind.Fix }.decision)
        val kept = (store.detail(asWritten) as SessionDetailResult.Found).detail
        assertEquals(wrong, kept.session.finalText)
        assertTrue(kept.suggestions.none { it.decision == Decision.Accepted })
    }

    @Test fun nothingIsRecordedWhileHistoryIsOff() = runBlocking<Unit> {
        enabled = false
        assertNull(send("Hola", Origin.Chat, clean("Hola")))
        assertEquals(0, store.count().first())
        assertTrue(store.recent(10).first().isEmpty())
    }

    @Test fun theCheckCorpusCarriesChatMessagesOnceWithFixSpansAndOrigin() = runBlocking<Unit> {
        val result = interpret(wrong, verdict)
        send(wrong, Origin.Chat, result, accept = setOf(EditKind.Fix, EditKind.Natural))
        // Typing the corrected text later in the chat is the writer's own writing, not a re-check of put-back text.
        send("Ayer fui a la playa", Origin.Chat, clean("Ayer fui a la playa"))
        send("Mañana voy al cine", Origin.Menu, clean("Mañana voy al cine").let {
            interpret("Mañana voy al cine", Verdict(Verdict.Status.Ok, language = "Spanish", hasErrors = true,
                corrected = "Mañana voy al cine.", fixes = listOf(VerdictChange("cine", "cine.", "Full stop"))))
        }, accept = setOf(EditKind.Fix))

        val utterances = CheckCorpusSource(store).utterances()
        assertEquals(listOf(wrong, "Ayer fui a la playa", "Mañana voy al cine"), utterances.map { it.text })
        assertEquals(listOf("chat", "chat", "menu"), utterances.map { it.origin })
        assertEquals(utterances.size, utterances.map { it.id }.distinct().size)
        // The fix marks "fue"; the accepted rewording alone corrects nothing.
        assertEquals(listOf(Span(8, 11)), utterances[0].corrected)
        assertTrue(utterances.all { it.source == "check" })
    }

    @Test fun patternsPickUpChatMistakesAndSayWhereTheyCameFrom() = runBlocking<Unit> {
        val result = interpret(wrong, verdict)
        send(wrong, Origin.Chat, result, accept = setOf(EditKind.Fix))
        val details = store.patternEvidence()!!
        val examples = PatternAnalyser.analyse(details).let { report -> report.patterns.flatMap { it.examples } + report.unpatterned }
        assertTrue(examples.any { it.from == "fue" && it.origin == "chat" })
    }

    companion object {
        private const val NAME = "chat-history-test"
    }
}
