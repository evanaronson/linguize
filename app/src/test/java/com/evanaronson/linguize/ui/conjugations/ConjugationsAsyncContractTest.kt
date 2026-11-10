package com.evanaronson.linguize.ui.conjugations

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.corpus.Corpus
import com.evanaronson.linguize.core.corpus.CorpusRead
import com.evanaronson.linguize.core.corpus.CorpusReadStatus
import com.evanaronson.linguize.core.corpus.CorpusSource
import com.evanaronson.linguize.core.corpus.CorpusSourceCoverage
import com.evanaronson.linguize.core.morphology.Morphology
import com.evanaronson.linguize.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConjugationsAsyncContractTest {
    @get:Rule val main = MainDispatcherRule()
    private val owners = mutableListOf<ViewModelStore>()
    private val morphology = Morphology()
    private val spanish = listOf(Utterance("es", "check", "es", "Hablo contigo", at = 1_000))
    private val catalan = listOf(Utterance("ca", "check", "ca", "Parlo amb tu", at = 2_000))

    @After fun clearViewModels() = owners.forEach { it.clear() }

    private fun model(read: suspend () -> List<Utterance>): ConjugationsViewModel {
        val owner = ViewModelStore().also { owners += it }
        return ViewModelProvider(owner, viewModelFactory {
            initializer { ConjugationsViewModel(read, { morphology }, { ZoneOffset.UTC }, main.dispatcher) }
        })[ConjugationsViewModel::class.java]
    }

    @Test fun refreshDuringAReadCountsTheNewerEvidenceAndIgnoresTheLateOldResult() = runTest(main.dispatcher) {
        val first = CompletableDeferred<List<Utterance>>()
        var reads = 0
        val model = model { if (reads++ == 0) withContext(NonCancellable) { first.await() } else catalan }
        model.refresh()
        runCurrent()
        model.refresh()
        advanceUntilIdle()
        assertEquals(2, reads)
        val current = model.state.value.reports
        assertEquals(1, current["ca"]?.utterances)
        assertEquals(0, current["es"]?.utterances)

        first.complete(spanish)
        advanceUntilIdle()
        assertSame(current, model.state.value.reports)
        assertFalse(model.state.value.failed)
    }

    @Test fun cancellationAndALateReadFailureCannotEndTheNewRequestsLoadingState() = runTest(main.dispatcher) {
        val first = CompletableDeferred<List<Utterance>>()
        val second = CompletableDeferred<List<Utterance>>()
        var reads = 0
        val model = model {
            if (reads++ == 0) withContext(NonCancellable) { first.await() } else second.await()
        }
        model.refresh()
        runCurrent()
        model.refresh()
        runCurrent()
        first.completeExceptionally(IOException("Old read failed"))
        runCurrent()
        assertTrue(model.state.value.loading)
        assertFalse(model.state.value.failed)

        second.complete(catalan)
        advanceUntilIdle()
        assertFalse(model.state.value.loading)
        assertEquals(1, model.state.value.reports["ca"]?.utterances)
    }

    @Test fun aLateOldSuccessCannotClearTheCurrentReadFailure() = runTest(main.dispatcher) {
        val first = CompletableDeferred<List<Utterance>>()
        var reads = 0
        val model = model {
            if (reads++ == 0) withContext(NonCancellable) { first.await() } else throw IOException("Current read failed")
        }
        model.refresh()
        runCurrent()
        model.refresh()
        advanceUntilIdle()
        assertTrue(model.state.value.failed)
        val failed = model.state.value

        first.complete(spanish)
        advanceUntilIdle()
        assertEquals(failed, model.state.value)
    }

    @Test fun selectingALanguageDuringTheReadKeepsTheSelectionAndBothReports() = runTest(main.dispatcher) {
        val pending = CompletableDeferred<List<Utterance>>()
        val model = model { pending.await() }
        model.refresh()
        runCurrent()
        model.select("ca")
        pending.complete(spanish + catalan)
        advanceUntilIdle()
        assertEquals("ca", model.state.value.shown)
        assertEquals(setOf("es", "ca"), model.state.value.reports.keys)
        assertEquals(1, model.state.value.report?.utterances)
    }

    @Test fun aFailedRefreshKeepsTheLastReportsAndCanRetry() = runTest(main.dispatcher) {
        var fail = false
        val model = model { if (fail) throw IOException("Read failed") else spanish }
        model.refresh()
        advanceUntilIdle()
        val report = model.state.value.reports
        fail = true
        model.refresh()
        advanceUntilIdle()
        assertSame(report, model.state.value.reports)
        assertTrue(model.state.value.failed)

        fail = false
        model.refresh()
        advanceUntilIdle()
        assertFalse(model.state.value.failed)
        assertEquals(1, model.state.value.reports["es"]?.utterances)
    }

    @Test fun partialCorpusEvidenceCannotReplaceCompleteReportsAndRetryUsesTheRecoveredSource() = runTest(main.dispatcher) {
        var status = CorpusReadStatus.Complete
        var items = spanish
        val source = object : CorpusSource {
            override val name = "check"
            override suspend fun read() = CorpusRead(items, listOf(CorpusSourceCoverage(name, status)))
            override suspend fun utterances() = read().requireComplete()
        }
        val corpus = Corpus(listOf(source))
        val model = model { corpus.utterances() }
        model.refresh()
        advanceUntilIdle()
        val before = model.state.value.reports
        status = CorpusReadStatus.Partial
        items = catalan
        model.refresh()
        advanceUntilIdle()
        assertTrue(model.state.value.failed)
        assertSame(before, model.state.value.reports)
        assertEquals(1, model.state.value.reports["es"]?.utterances)

        status = CorpusReadStatus.Complete
        model.refresh()
        advanceUntilIdle()
        assertFalse(model.state.value.failed)
        assertEquals(0, model.state.value.reports["es"]?.utterances)
        assertEquals(1, model.state.value.reports["ca"]?.utterances)
    }

    @Test fun anUnreadableInitialCorpusIsAFailureAndASuccessfulEmptyRetryIsNot() = runTest(main.dispatcher) {
        var failed = true
        val source = object : CorpusSource {
            override val name = "check"
            override suspend fun utterances(): List<Utterance> {
                if (failed) throw IOException("History unavailable")
                return emptyList()
            }
        }
        val model = model { Corpus(listOf(source)).utterances() }
        model.refresh()
        advanceUntilIdle()
        assertTrue(model.state.value.failed)
        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.reports.isEmpty())

        failed = false
        model.refresh()
        advanceUntilIdle()
        assertFalse(model.state.value.failed)
        assertEquals(setOf("es", "ca"), model.state.value.reports.keys)
        assertTrue(model.state.value.reports.values.all { it.utterances == 0 })
    }
}
