package com.evanaronson.linguize

import com.evanaronson.linguize.data.lab.ExplanationStore
import com.evanaronson.linguize.data.lab.SavedExplanation
import com.evanaronson.linguize.history.HistoryCursor
import com.evanaronson.linguize.history.HistoryPage
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.patterns.FakePatternHistory
import com.evanaronson.linguize.history.patterns.PatternAnalyser
import com.evanaronson.linguize.history.patterns.PatternFixtures.check
import com.evanaronson.linguize.history.patterns.PatternFixtures.fix
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.lab.PatternsPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PatternsLabConcurrencyTest {
    private val history = FakePatternHistory(listOf(
        check("a", 1, fix("tambien", "también")),
        check("b", 2, fix("aqui", "aquí")),
    ))
    private val notes = Notes()

    private fun TestScope.service(
        source: HistoryStore = history,
        generate: suspend () -> String = { REPLY },
    ) = PatternsLab(
        source, notes, FakePreferences(), { "key" },
        mapOf(Provider.Gemini to object : ProviderClient {
            override suspend fun generate(key: String, model: String, request: GenerationRequest) = generate()
            override suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer = error("Unused")
            override suspend fun models(key: String): List<String> = emptyList()
        }),
        { PatternsPrompt("Explain patterns") }, io = StandardTestDispatcher(testScheduler),
    )

    @Test fun deleteInvalidatesAnExplanationWithoutRelyingOnTheCallersCancellation() = runTest {
        val answer = CompletableDeferred<String>()
        val lab = service { withContext(NonCancellable) { answer.await() } }
        val report = lab.load()!!.report
        val pending = async { lab.explain(report) }
        runCurrent()

        lab.deleteExplanation()
        answer.complete(REPLY)
        advanceUntilIdle()
        assertTrue(pending.isCancelled)
        assertNull(notes.saved)
    }

    @Test fun aNewerExplanationOwnsTheSavedResultEvenWhenTheOlderProviderReturnsLast() = runTest {
        val first = CompletableDeferred<String>()
        val second = CompletableDeferred<String>()
        var calls = 0
        val lab = service { if (calls++ == 0) first.await() else second.await() }
        val oldReport = lab.load()!!.report
        val older = async { lab.explain(oldReport) }
        runCurrent()
        history.sessions["c"] = check("c", 3, fix("cafe", "café"))
        val newReport = lab.load()!!.report
        val newer = async { lab.explain(newReport) }
        runCurrent()
        second.complete(REPLY)
        advanceUntilIdle()
        assertEquals(newReport.fingerprint, newer.await().explanation?.fingerprint)

        first.complete(REPLY)
        advanceUntilIdle()
        assertTrue(older.isCancelled)
        assertEquals(newReport.fingerprint, notes.saved?.fingerprint)
        assertEquals(setOf("a", "b", "c"), notes.saved?.sources?.toSet())
    }

    @Test fun anOldHistoryReadCannotDeleteAnExplanationFromNewerEvidence() = runTest {
        val release = CompletableDeferred<Unit>()
        var holdNextRead = true
        val delayed = object : HistoryStore by history {
            override suspend fun page(before: HistoryCursor?, limit: Int): HistoryPage? {
                val captured = history.page(before, limit)
                if (holdNextRead) {
                    holdNextRead = false
                    release.await()
                }
                return captured
            }
        }
        val lab = service(delayed)
        val older = async { lab.load() }
        runCurrent()
        history.sessions["c"] = check("c", 3, fix("cafe", "café"))
        val newerReport = PatternAnalyser.analyse(history.sessions.values.toList())
        val explain = async { lab.explain(newerReport) }
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()

        assertFalse(older.isCancelled)
        assertEquals(newerReport.fingerprint, explain.await().explanation?.fingerprint)
        assertEquals(newerReport.fingerprint, notes.saved?.fingerprint)
        assertEquals(setOf("a", "b", "c"), notes.saved?.sources?.toSet())
    }

    @Test fun aCancelledHistoryReadCannotReconcileUsingItsObsoleteEvidence() = runTest {
        val release = CompletableDeferred<Unit>()
        var holdNextRead = false
        val delayed = object : HistoryStore by history {
            override suspend fun page(before: HistoryCursor?, limit: Int): HistoryPage? {
                val captured = history.page(before, limit)
                if (holdNextRead) {
                    holdNextRead = false
                    withContext(NonCancellable) { release.await() }
                }
                return captured
            }
        }
        val lab = service(delayed)
        lab.explain(lab.load()!!.report)
        val saved = notes.saved
        val removed = history.sessions.remove("b")!!
        holdNextRead = true
        val oldRead = async { lab.load() }
        runCurrent()
        history.sessions["b"] = removed
        oldRead.cancel()
        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(oldRead.isCancelled)
        assertEquals(saved, notes.saved)
    }

    private class Notes : ExplanationStore {
        var saved: SavedExplanation? = null
        override fun read() = saved
        override fun write(explanation: SavedExplanation) { saved = explanation }
        override fun delete() { saved = null }
    }

    companion object {
        private const val REPLY = """{"summary":"Remember written accents.","tips":[{"pattern":"accents","tip":"Check accent marks."}]}"""
    }
}
