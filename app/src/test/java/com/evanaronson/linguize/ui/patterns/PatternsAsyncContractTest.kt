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
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.lab.PatternsPrompt
import com.evanaronson.linguize.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
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

    private fun patterns(revisions: Flow<Long> = history.revisions(), language: String? = "es"): PatternsViewModel {
        val lab = PatternsLab(
            history, notes, FakePreferences(), { "test-key" }, mapOf(Provider.Gemini to client),
            { PatternsPrompt("Explain recurring patterns") }, now = { 42L }, io = main.dispatcher,
        )
        val owner = ViewModelStore().also { owners += it }
        return ViewModelProvider(owner, viewModelFactory {
            initializer { PatternsViewModel(lab, revisions) }
        })[PatternsViewModel::class.java].also { it.setLanguage(language) }
    }

    @Test fun initialReadDoesNotDependOnARevisionEmission() = runTest(main.dispatcher) {
        val patterns = patterns(revisions = emptyFlow(), language = null)
        runCurrent()
        assertTrue(patterns.state.value.loading)
        assertTrue(history.detailReads.isEmpty())
        advanceTimeBy(PatternsViewModel.REBUILD_DELAY_MS - 1)
        runCurrent()
        assertTrue(history.detailReads.isEmpty())
        advanceUntilIdle()

        assertFalse(patterns.state.value.loading)
        assertEquals(spanishSources + catalanSources, patterns.state.value.snapshot?.report?.evidenceSessions)
        assertEquals(4, history.detailReads.size)
    }

    @Test fun enteringAnotherScopeInvalidatesNowAndReadsOnceAfterTheDelay() = runTest(main.dispatcher) {
        val patterns = patterns()
        advanceUntilIdle()
        history.detailReads.clear()

        patterns.enter("ca")
        assertEquals("ca", patterns.state.value.language)
        assertNull(patterns.state.value.snapshot)
        assertTrue(patterns.state.value.loading)
        runCurrent()
        assertTrue(history.detailReads.isEmpty())
        advanceUntilIdle()

        assertEquals(catalanSources, patterns.state.value.snapshot?.report?.evidenceSessions)
        assertEquals(4, history.detailReads.size)
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


    @Test fun anUnavailableFinalEvidenceReadKeepsTheReportAndOffersHistoryRetry() = runTest(main.dispatcher) {
        val patterns = patterns()
        advanceUntilIdle()
        val snapshot = patterns.state.value.snapshot
        patterns.explain()
        runCurrent()
        history.failPage = true
        client.answer.complete(REPLY)
        advanceUntilIdle()

        assertSame(snapshot, patterns.state.value.snapshot)
        assertTrue(patterns.state.value.failed)
        assertFalse(patterns.state.value.explaining)
        assertNull(patterns.state.value.explainFailure)
        assertNull(notes.saved)
        assertEquals(1, client.requests.size)

        history.failPage = false
        patterns.refresh()
        advanceUntilIdle()
        assertFalse(patterns.state.value.failed)
        assertEquals(400, patterns.state.value.snapshot?.window?.limit)
        assertEquals(1, client.requests.size)
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
    fun aSpanishExplanationCompletingAfterALanguageChangeCannotReplaceTheCatalanReport() = runTest(main.dispatcher) {
        client.ignoreCancellation = true
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
        assertNull(notes.saved)
    }

    @Test fun aScopeChangeBeforeTheExplanationStartsNeverSendsTheOldReport() = runTest(main.dispatcher) {
        val patterns = patterns()
        advanceUntilIdle()
        patterns.explain()
        patterns.setLanguage("ca")
        advanceUntilIdle()
        assertTrue(client.requests.isEmpty())
        assertEquals(catalanSources, patterns.state.value.snapshot?.report?.evidenceSessions)
        assertFalse(patterns.state.value.explaining)
    }

    @Test fun anOldExplanationFailureCannotClearTheNewLanguagesLoadingState() = runTest(main.dispatcher) {
        client.ignoreCancellation = true
        val patterns = patterns()
        advanceUntilIdle()
        patterns.explain()
        runCurrent()
        val spanishReply = client.answer

        patterns.setLanguage("ca")
        advanceUntilIdle()
        client.answer = CompletableDeferred()
        patterns.explain()
        runCurrent()
        assertEquals(2, client.requests.size)
        assertTrue(patterns.state.value.explaining)

        spanishReply.completeExceptionally(CheckFailure(CheckFailure.Reason.Timeout))
        runCurrent()
        assertTrue(patterns.state.value.explaining)
        assertNull(patterns.state.value.explainFailure)
        assertEquals(catalanSources, patterns.state.value.snapshot?.report?.evidenceSessions)

        client.answer.complete(REPLY)
        advanceUntilIdle()
        assertEquals(catalanSources, notes.saved?.sources?.toSet())
    }

    @Test fun anOldExplanationSuccessCannotClearTheNewLanguagesFailure() = runTest(main.dispatcher) {
        client.ignoreCancellation = true
        val patterns = patterns()
        advanceUntilIdle()
        patterns.explain()
        runCurrent()
        val spanishReply = client.answer

        patterns.setLanguage("ca")
        advanceUntilIdle()
        client.answer = CompletableDeferred()
        patterns.explain()
        runCurrent()
        client.answer.completeExceptionally(CheckFailure(CheckFailure.Reason.Timeout))
        advanceUntilIdle()
        val failed = patterns.state.value
        assertEquals(CheckFailure.Reason.Timeout, failed.explainFailure?.reason)

        spanishReply.complete(REPLY)
        advanceUntilIdle()
        assertEquals(failed, patterns.state.value)
        assertNull(notes.saved)
    }

    @Test fun aHistoryRevisionInvalidatesTheExplanationBeforeTheRebuildDebounce() = runTest(main.dispatcher) {
        client.ignoreCancellation = true
        val patterns = patterns()
        advanceUntilIdle()
        patterns.explain()
        runCurrent()

        history.delete("es-1")
        runCurrent()
        assertTrue(patterns.state.value.loading)
        client.answer.complete(REPLY)
        runCurrent()
        assertNull(notes.saved)
        assertFalse(patterns.state.value.explaining)
        advanceUntilIdle()
        assertEquals(setOf("es-2"), patterns.state.value.snapshot?.report?.evidenceSessions)
    }

    @Test fun deletingWhileTheProviderIsPendingCannotRestoreTheExplanation() = runTest(main.dispatcher) {
        client.ignoreCancellation = true
        val patterns = patterns()
        advanceUntilIdle()
        patterns.explain()
        runCurrent()
        patterns.deleteExplanation()
        advanceUntilIdle()
        client.answer.complete(REPLY)
        advanceUntilIdle()
        assertNull(notes.saved)
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
        var ignoreCancellation = false
        val requests = mutableListOf<GenerationRequest>()
        override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
            requests += request
            val pending = answer
            return if (ignoreCancellation) withContext(NonCancellable) { pending.await() } else pending.await()
        }
        override suspend fun models(key: String): List<String> = emptyList()
    }

    companion object {
        private const val REPLY = """{"summary":"Remember written accents.","tips":[{"pattern":"accents","tip":"Check accent marks."}]}"""
    }
}
