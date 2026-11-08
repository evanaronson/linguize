package com.evanaronson.linguize.ui.secondopinion

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.FakeClient
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.VerdictChange
import com.evanaronson.linguize.core.opinion.Agreement
import com.evanaronson.linguize.core.opinion.SecondAnswer
import com.evanaronson.linguize.data.SecondOpinionPlan
import com.evanaronson.linguize.data.SecondOpinionTarget
import com.evanaronson.linguize.history.CheckHistory
import com.evanaronson.linguize.history.HistoryCursor
import com.evanaronson.linguize.history.HistoryEnvironment
import com.evanaronson.linguize.history.HistoryPage
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.ReuseKey
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionDetailResult
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SessionRecording
import com.evanaronson.linguize.history.SessionSummary
import com.evanaronson.linguize.history.SuggestionRecord
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.OutputStream
import java.util.Collections

/**
 * The second opinion on fake providers: it repeats the card's check with only the service
 * and model changed, compares the answers, belongs to the check it was asked for, and never
 * reaches history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SecondOpinionControllerTest {
    private val text = "Com estas? Fins dema"

    private fun answer(verdict: Verdict) = ModelAnswer(verdict, Json.encodeToString(verdict))

    private val geminiVerdict = Verdict(
        Verdict.Status.Ok, language = "Catalan", hasErrors = true, corrected = "Com estàs? Fins demà",
        fixes = listOf(VerdictChange("estas", "estàs", confidence = 9, impact = 6), VerdictChange("dema", "demà", confidence = 9, impact = 6)),
    )
    private val openaiVerdict = Verdict(
        Verdict.Status.Ok, language = "Catalan", hasErrors = true, corrected = "Com està? Fins demà",
        fixes = listOf(VerdictChange("estas", "està", confidence = 5, impact = 6), VerdictChange("dema", "demà", confidence = 9, impact = 6)),
    )

    private val preferences = FakePreferences().apply { punctuation = Punctuation.Strict }
    private val gemini = FakeClient { answer(geminiVerdict) }
    private val openai = FakeClient { answer(openaiVerdict) }
    private val keys = mutableMapOf(Provider.Gemini to "g-key", Provider.OpenAI to "o-key")
    private val checker = Checker(preferences, { keys[it] }, Prompt(system = "instructions", schemaJson = "{}"),
        mapOf(Provider.Gemini to gemini, Provider.OpenAI to openai))

    private val store = CountingStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val history = CheckHistory(store, object : HistoryEnvironment {
        override val enabled = true
        override val deviceId = "device"
        override val appVersion = "1.0"
    }, scope)
    @Volatile private var plan: SecondOpinionPlan = SecondOpinionPlan.Ready(SecondOpinionTarget(Provider.OpenAI, "gpt-x"))
    private val viewModels = ViewModelStore()
    private val check = ViewModelProvider(viewModels, viewModelFactory {
        initializer { CheckViewModel(checker = { checker }, history = history, secondOpinionPlan = { plan }) }
    })[CheckViewModel::class.java]
    private val opinion get() = check.secondOpinion

    @After fun stop() {
        viewModels.clear()
        scope.cancel()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }

    private fun checked(language: Language? = Language.Catalan) {
        check.check(text, language, Origin.Menu)
        waitFor("the check") { check.state is CardState.Done }
    }

    private fun answered(): SecondOpinionState.Answered {
        opinion.prepare()
        waitFor("the plan") { opinion.state is SecondOpinionState.Ready }
        opinion.ask()
        waitFor("the second opinion") { opinion.state is SecondOpinionState.Answered || opinion.state is SecondOpinionState.Failed }
        return opinion.state as SecondOpinionState.Answered
    }

    private fun attempts(): Int = ReflectionHelpers.getField<SessionRecording>(history, "recording").session.attempts.size

    @Test fun repeatsTheSameCheckWithOnlyTheServiceAndModelChanged() {
        checked()
        val state = answered()

        assertEquals(1, gemini.checks.size)
        assertEquals(1, openai.checks.size)
        val (firstKey, firstModel, firstRequest) = gemini.checks.single()
        val (secondKey, secondModel, secondRequest) = openai.checks.single()
        assertEquals("g-key" to Provider.Gemini.recommendedModel, firstKey to firstModel)
        assertEquals("o-key" to "gpt-x", secondKey to secondModel)
        assertEquals(firstRequest, secondRequest)
        assertEquals(Punctuation.Strict, secondRequest.punctuation)
        assertEquals(Language.Catalan, secondRequest.language)
        assertEquals(SecondOpinionTarget(Provider.Gemini, Provider.Gemini.recommendedModel), state.checkedWith)
        assertEquals(SecondOpinionTarget(Provider.OpenAI, "gpt-x"), state.target)
    }

    @Test fun marksEachSuggestionOnTheCard() {
        checked()
        val comparison = (answered().answer as SecondAnswer.Compared).comparison
        val fixes = ((check.state as CardState.Done).result as CheckResult.Reviewed).revision.edits(EditKind.Fix)

        val estas = fixes.single { it.from == "estas" }
        val dema = fixes.single { it.from == "dema" }
        val disagreement = comparison.forFirstEdit(estas.id)!!
        assertEquals(Agreement.Disagree, disagreement.agreement)
        assertEquals("estàs", disagreement.shown.first)
        assertEquals("està", disagreement.shown.second)
        assertEquals(Agreement.Agree, comparison.forFirstEdit(dema.id)?.agreement)
    }

    @Test fun neverReachesHistory() {
        checked()
        val before = attempts()
        answered()
        assertEquals(before, attempts())
        assertEquals(1, before)
        check.dismiss(applied = false)
        history.awaitWrites(5_000)
        assertEquals(0, store.saves.size)
        assertEquals(0, store.closed.size)
    }

    @Test fun keepsTheWritersClarificationsInTheSecondCheck() {
        checked()
        check.clarifyMeaning("I am asking a friend")
        waitFor("the re-check") { check.state is CardState.Done && gemini.checks.size == 2 }
        answered()
        assertEquals(listOf(Settled.meaning("I am asking a friend")), openai.checks.single().third.settled)
    }

    @Test fun acceptingChangesKeepsTheSecondOpinion() {
        checked()
        answered()
        val reviewed = (check.state as CardState.Done).result as CheckResult.Reviewed
        check.accept(reviewed.revision.edits.first().id)
        assertTrue(opinion.state is SecondOpinionState.Answered)
    }

    @Test fun belongsOnlyToTheCheckItWasAskedFor() {
        checked()
        answered()
        gemini.answer = { answer(openaiVerdict) }
        check.recheck()
        waitFor("the re-check") { check.state is CardState.Done && gemini.checks.size == 2 }
        assertNull(opinion.state)
        opinion.prepare()
        waitFor("the plan") { opinion.state != null }
        assertTrue(opinion.state is SecondOpinionState.Ready)
    }

    @Test fun anUnavailablePlanOffersNothing() {
        plan = SecondOpinionPlan.NoKey(Provider.OpenAI)
        checked()
        opinion.prepare()
        waitFor("the plan") { opinion.state != null }
        assertEquals(SecondOpinionPlan.NoKey(Provider.OpenAI), (opinion.state as SecondOpinionState.Unavailable).plan)
        opinion.ask()
        assertTrue(opinion.state is SecondOpinionState.Unavailable)
        assertTrue(openai.checks.isEmpty())
    }

    @Test fun aFailureCanBeAskedAgain() {
        checked()
        openai.answer = { throw CheckFailure(CheckFailure.Reason.Offline) }
        opinion.prepare()
        waitFor("the plan") { opinion.state is SecondOpinionState.Ready }
        opinion.ask()
        waitFor("the failure") { opinion.state is SecondOpinionState.Failed }
        assertEquals(CheckFailure.Reason.Offline, (opinion.state as SecondOpinionState.Failed).reason)
        // The card's own check is untouched.
        assertTrue(check.state is CardState.Done)

        openai.answer = { answer(openaiVerdict) }
        opinion.ask()
        waitFor("the second opinion") { opinion.state is SecondOpinionState.Answered }
        assertEquals(2, openai.checks.size)
    }

    @Test fun aSecondAnswerThatCannotReadTheTextSaysSo() {
        openai.answer = { answer(Verdict(Verdict.Status.Unclear)) }
        checked()
        assertEquals(SecondAnswer.Unclear, answered().answer)
    }

    @Test fun nothingToCompareBeforeTheCheckFinishes() {
        assertNull(opinion.requestKey)
        opinion.prepare()
        opinion.ask()
        assertNull(opinion.state)
        assertTrue(openai.checks.isEmpty())
    }

    @Test fun theSuspendingStepComparesWithoutAViewModel() = runBlocking {
        val first = checker.check(text, null, context = checker.context())
        val request = SecondOpinionRequest(text, null, emptyList(), first.context, first.result as CheckResult.Reviewed)
        val answer = secondOpinion(checker, request, SecondOpinionTarget(Provider.Gemini, "gemini-other"))
        val comparison = (answer as SecondAnswer.Compared).comparison
        assertTrue(comparison.allAgree)
        assertEquals("gemini-other", gemini.checks.last().second)
        assertEquals(Judgments.Both, gemini.checks.last().third.judgments)
    }

    /** Counts what history asks it to keep. */
    private class CountingStore : HistoryStore {
        val saves: MutableList<SessionRecord> = Collections.synchronizedList(mutableListOf())
        val closed: MutableList<SessionRecord> = Collections.synchronizedList(mutableListOf())
        override suspend fun save(session: SessionRecord) { saves += session }
        override suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>) { closed += session }
        override suspend fun reusable(key: ReuseKey, since: Long): SessionDetail? = null
        override suspend fun markAbandoned(now: Long) = Unit
        override fun count(): Flow<Int> = flowOf(0)
        override fun since(): Flow<Long?> = flowOf(null)
        override fun recent(limit: Int): Flow<List<SessionSummary>> = flowOf(emptyList())
        override fun revisions(): Flow<Long> = flowOf(0L)
        override suspend fun page(before: HistoryCursor?, limit: Int) = HistoryPage(emptyList(), null)
        override suspend fun detail(id: String) = SessionDetailResult.Missing
        override suspend fun delete(id: String) = true
        override suspend fun clear() = Unit
        override suspend fun export(out: OutputStream) = true
    }
}
