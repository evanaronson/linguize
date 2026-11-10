package com.evanaronson.linguize.ui.patterns

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.ExplanationStatus
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.PatternsLab
import com.evanaronson.linguize.data.lab.ExplanationStore
import com.evanaronson.linguize.data.lab.SavedExplanation
import com.evanaronson.linguize.history.patterns.FakePatternHistory
import com.evanaronson.linguize.history.patterns.PatternFixtures.check
import com.evanaronson.linguize.history.patterns.PatternFixtures.fix
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.lab.PatternsPrompt
import com.evanaronson.linguize.testing.KnownBonanzaDefect
import com.evanaronson.linguize.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the view model, analyser and explanation service together with a gated provider. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PatternsAsyncContractTest {
    @get:Rule val main = MainDispatcherRule()
    private val owners = mutableListOf<ViewModelStore>()
    private val history = FakePatternHistory(listOf(
        check("es-1", 1, fix("tambien", "también"), language = "es"),
        check("es-2", 2, fix("aqui", "aquí"), language = "es"),
        check("ca-1", 3, fix("aixo", "això"), language = "ca"),
        check("ca-2", 4, fix("cafe", "cafè"), language = "ca"),
    ))
    private val notes = Notes()
    private val client = GatedClient()
    private val spanishSources = setOf("es-1", "es-2")
    private val catalanSources = setOf("ca-1", "ca-2")

    @After fun clearViewModels() = owners.forEach { it.clear() }

    private fun patterns(): PatternsViewModel {
        val lab = PatternsLab(
            history, notes, FakePreferences(), { "test-key" }, mapOf(Provider.Gemini to client),
            { PatternsPrompt("Explain recurring patterns") }, now = { 42L }, io = main.dispatcher,
        )
        val owner = ViewModelStore().also { owners += it }
        return ViewModelProvider(owner, viewModelFactory {
            initializer { PatternsViewModel(lab, history.revisions()) }
        })[PatternsViewModel::class.java].also { it.setLanguage("es") }
    }

    @Test fun delayedExplanationKeepsItsScopeAndSavesOnlyThatScopesSources() = runTest(main.dispatcher) {
        val patterns = patterns()
        advanceUntilIdle()
        val report = patterns.state.value.snapshot!!.report
        assertEquals(spanishSources, report.evidenceSessions)
        assertTrue(patterns.state.value.canExplain)

        patterns.explain()
        runCurrent()
        assertEquals(1, client.requests.size)
        assertTrue(patterns.state.value.explaining)
        assertSame(report, patterns.state.value.snapshot?.report)

        client.answer.complete(REPLY)
        advanceUntilIdle()
        val state = patterns.state.value
        assertEquals("es", state.language)
        assertFalse(state.explaining)
        assertNull(state.explainFailure)
        assertEquals(report.fingerprint, state.snapshot?.report?.fingerprint)
        assertEquals(ExplanationStatus.Current, state.snapshot?.status)
        assertEquals("Remember written accents.", state.snapshot?.explanation?.summary)
        assertEquals(spanishSources, notes.saved?.sources?.toSet())
    }

    @Test fun failedExplanationKeepsTheReportAndAllowsAnotherRequest() = runTest(main.dispatcher) {
        val patterns = patterns()
        advanceUntilIdle()
        val snapshot = patterns.state.value.snapshot
        patterns.explain()
        runCurrent()

        client.answer.completeExceptionally(CheckFailure(CheckFailure.Reason.Timeout))
        advanceUntilIdle()
        assertSame(snapshot, patterns.state.value.snapshot)
        assertFalse(patterns.state.value.explaining)
        assertEquals(CheckFailure.Reason.Timeout, patterns.state.value.explainFailure?.reason)
        assertNull(notes.saved)

        client.answer = CompletableDeferred()
        patterns.explain()
        runCurrent()
        assertEquals(2, client.requests.size)
        assertTrue(patterns.state.value.explaining)
        assertNull(patterns.state.value.explainFailure)
        client.answer.complete(REPLY)
        advanceUntilIdle()
        assertFalse(patterns.state.value.explaining)
        assertNotNull(patterns.state.value.snapshot?.explanation)
    }

    @Test fun aFailedHistoryRefreshKeepsTheLastReportAndRecoversOnRetry() = runTest(main.dispatcher) {
        val patterns = patterns()
        advanceUntilIdle()
        val snapshot = patterns.state.value.snapshot
        history.failPage = true
        patterns.refresh()
        advanceUntilIdle()
        assertTrue(patterns.state.value.failed)
        assertFalse(patterns.state.value.loading)
        assertSame(snapshot, patterns.state.value.snapshot)
        assertTrue(client.requests.isEmpty())

        history.failPage = false
        patterns.refresh()
        advanceUntilIdle()
        assertFalse(patterns.state.value.failed)
        assertEquals(snapshot?.report?.fingerprint, patterns.state.value.snapshot?.report?.fingerprint)
    }

    @Test
    @Category(KnownBonanzaDefect::class)
    fun aSpanishExplanationCompletingAfterALanguageChangeCannotReplaceTheCatalanReport() = runTest(main.dispatcher) {
        val patterns = patterns()
        advanceUntilIdle()
        assertEquals("es", patterns.state.value.language)
        assertEquals(spanishSources, patterns.state.value.snapshot?.report?.evidenceSessions)
        patterns.explain()
        runCurrent()
        assertEquals(1, client.requests.size)
        assertTrue(patterns.state.value.explaining)

        patterns.setLanguage("ca")
        advanceUntilIdle()
        val catalan = patterns.state.value.snapshot!!
        assertEquals(catalanSources, catalan.report.evidenceSessions)
        assertEquals("ca", patterns.state.value.language)

        client.answer.complete(REPLY)
        advanceUntilIdle()

        // The selected language and the report it labels must remain one coherent result.
        assertEquals("ca", patterns.state.value.language)
        assertEquals(catalan.report.fingerprint, patterns.state.value.snapshot?.report?.fingerprint)
        assertEquals(catalanSources, patterns.state.value.snapshot?.report?.evidenceSessions)
        assertNull(patterns.state.value.snapshot?.explanation)
        assertFalse(patterns.state.value.explaining)
    }

    private class Notes : ExplanationStore {
        var saved: SavedExplanation? = null
        override fun read() = saved
        override fun write(explanation: SavedExplanation) { saved = explanation }
        override fun delete() { saved = null }
    }

    private class GatedClient : ProviderClient {
        var answer = CompletableDeferred<String>()
        val requests = mutableListOf<GenerationRequest>()
        override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
            requests += request
            return answer.await()
        }
        override suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer = error("Unused")
        override suspend fun models(key: String): List<String> = emptyList()
    }

    companion object {
        private const val REPLY = """{"summary":"Remember written accents.","tips":[{"pattern":"accents","tip":"Check accent marks."}]}"""
    }
}
