package com.evanaronson.linguize.ui.replycontext

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.QUESTION
import com.evanaronson.linguize.QUESTION_JSON
import com.evanaronson.linguize.QUESTION_VERDICT
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.history.CheckHistory
import com.evanaronson.linguize.history.HistoryEnvironment
import com.evanaronson.linguize.history.HistoryPage
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.ReuseKey
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionDetailResult
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SessionSummary
import com.evanaronson.linguize.history.SuggestionRecord
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.lab.ReplyPrompt
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.OutputStream
import java.util.Collections

/** Lab: the card's view model with a message to reply to: what it sends, when it checks again, and what it records. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReplyContextViewModelTest {
    private val preferences = FakePreferences()
    private val client = Client()
    private val prompt = Prompt(system = "instructions", schemaJson = """{"required":["status"]}""")
    private val checker = Checker(preferences, { "key" }, prompt, Provider.entries.associateWith { client },
        replyPrompt = { ReplyPrompt("reply rules") })
    private val store = Store()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history = CheckHistory(store, object : HistoryEnvironment {
        override val enabled = true
        override val deviceId = "device"
        override val appVersion = "1.0"
    }, scope)
    private val viewModels = ViewModelStore()
    private val check = ViewModelProvider(
        viewModels,
        viewModelFactory { initializer { CheckViewModel(checker = { checker }, history = history) } },
    )[CheckViewModel::class.java]
    private val actions = ReplyContextActions.of(check)

    @After
    fun stop() {
        viewModels.clear()
        scope.cancel()
    }

    @Test
    fun withNothingAttachedTheShippedCheckRuns() {
        check.check(QUESTION, null, Origin.Tester)
        done()
        assertEquals(1, client.checks.size)
        assertTrue(client.generations.isEmpty())
        assertNull(actions.message)
    }

    @Test
    fun attachingBeforeACheckIsUsedByIt() {
        assertTrue(actions.attach("  ¿Vienes?  "))
        assertEquals("¿Vienes?", check.replyingTo)
        assertNull(check.state)
        settle()
        assertTrue(client.generations.isEmpty())

        check.check(QUESTION, null, Origin.Tester)
        done()
        assertTrue(client.checks.isEmpty())
        assertTrue(client.generations.single().user.startsWith("Replying to: {\"message\":\"¿Vienes?\"}\n"))
    }

    @Test
    fun attachingOnAnOpenCardChecksAgainKeepingAcceptedChanges() {
        check.check(QUESTION, null, Origin.Menu)
        done()
        check.acceptAll(EditKind.Fix)
        val accepted = (done().result as CheckResult.Reviewed).revision.acceptedCount

        assertTrue(actions.attach("Com estàs?"))
        waitFor("the check in context") { client.generations.size == 1 && check.state is CardState.Done }
        assertEquals(accepted, (done().result as CheckResult.Reviewed).revision.acceptedCount)

        // The same message again changes nothing.
        assertTrue(actions.attach("Com estàs?"))
        settle()
        assertEquals(1, client.generations.size)
    }

    @Test
    fun removingGoesBackToTheShippedCheck() {
        actions.attach("Hola")
        check.check(QUESTION, null, Origin.Tester)
        done()
        actions.remove()
        assertNull(check.replyingTo)
        waitFor("the shipped check") { client.checks.size == 1 && check.state is CardState.Done }
        assertEquals(1, client.generations.size)
    }

    @Test
    fun aBlankOrTooLongMessageIsNotAttached() {
        assertFalse(actions.attach("   "))
        assertFalse(actions.attach("a".repeat(ReplyPrompt.MAX_CHARS + 1)))
        assertNull(check.replyingTo)
    }

    @Test
    fun backFromSettingsDoesNotCheckAgainJustBecauseOfTheMessage() {
        actions.attach("Hola")
        check.check(QUESTION, null, Origin.Tester)
        done()
        check.recheckIfStale()
        settle()
        assertEquals(1, client.generations.size)
    }

    @Test
    fun historyKeepsTheAnswerButNotTheMessage() {
        actions.attach("Un missatge privat")
        check.check(QUESTION, null, Origin.Menu)
        done()
        check.acceptAll(EditKind.Fix)
        check.dismiss(applied = true)
        history.awaitWrites(5_000)
        val session = store.closed.single()
        assertTrue(session.promptHash.startsWith(prompt.hash + "-r"))
        assertFalse("Un missatge privat" in session.toString())
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }

    private fun settle() = repeat(25) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(4)
    }

    private fun done(): CardState.Done {
        waitFor("an answer") { check.state is CardState.Done }
        return check.state as CardState.Done
    }

    private class Client : ProviderClient {
        val checks: MutableList<CheckRequest> = Collections.synchronizedList(mutableListOf())
        val generations: MutableList<GenerationRequest> = Collections.synchronizedList(mutableListOf())

        override suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer {
            checks += request
            return ModelAnswer(QUESTION_VERDICT.copy(fixes = FIXED.fixes, language = "Catalan"), FIXED_JSON)
        }

        override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
            generations += request
            return FIXED_JSON
        }

        override suspend fun models(key: String): List<String> = emptyList()
    }

    private class Store : HistoryStore {
        val closed: MutableList<SessionRecord> = Collections.synchronizedList(mutableListOf())
        override suspend fun save(session: SessionRecord) = Unit
        override suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>) { closed += session }
        override suspend fun reusable(key: ReuseKey, since: Long): SessionDetail? = null
        override suspend fun markAbandoned(now: Long) = Unit
        override fun count(): Flow<Int> = flowOf(0)
        override fun since(): Flow<Long?> = flowOf(null)
        override fun recent(limit: Int): Flow<List<SessionSummary>> = flowOf(emptyList())
        override fun revisions(): Flow<Long> = flowOf(0L)
        override suspend fun page(before: com.evanaronson.linguize.history.HistoryCursor?, limit: Int) =
            HistoryPage(emptyList(), null)
        override suspend fun detail(id: String) = SessionDetailResult.Missing
        override suspend fun delete(id: String) = true
        override suspend fun clear() = Unit
        override suspend fun export(out: OutputStream) = true
    }

    private companion object {
        const val FIXED_JSON = "{\"status\":\"ok\",\"language\":\"Catalan\",\"meaning\":\"How are you?\",\"assumptions\":[]," +
            "\"has_errors\":true,\"corrected\":\"Com estàs?\",\"fixes\":[{\"from\":\"estas\",\"to\":\"estàs\",\"why\":\"Accent\"," +
            "\"confidence\":10,\"impact\":3,\"assumption_indices\":[]}],\"more_natural\":false,\"natural\":\"\",\"natural_changes\":[]}"
        val FIXED = Prompt(system = "", schemaJson = "{}").parseVerdict(FIXED_JSON)
    }
}
