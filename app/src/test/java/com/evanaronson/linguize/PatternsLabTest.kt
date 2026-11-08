package com.evanaronson.linguize

import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.data.lab.ExplanationStore
import com.evanaronson.linguize.data.lab.SavedExplanation
import com.evanaronson.linguize.history.patterns.FakePatternHistory
import com.evanaronson.linguize.history.patterns.PatternAnalyser
import com.evanaronson.linguize.history.patterns.PatternFixtures.check
import com.evanaronson.linguize.history.patterns.PatternFixtures.fix
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.lab.PatternsPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PatternsLabTest {
    private val reply = """{"summary":"Most fixes are accents.","tips":[{"pattern":"accents","tip":"Words ending in -ción take an accent."}]}"""

    private class Notes : ExplanationStore {
        var saved: SavedExplanation? = null
        override fun read() = saved
        override fun write(explanation: SavedExplanation) { saved = explanation }
        override fun delete() { saved = null }
    }

    private class Preferences : CheckPreferences {
        override val provider = Provider.Gemini
        override val punctuation = Punctuation.Moderate
        override val judgments = Judgments.Both
        override val nativeLanguage = "English"
        override fun model(provider: Provider): String? = "gemini-test"
    }

    private class Client(var answer: String) : ProviderClient {
        val requests = mutableListOf<GenerationRequest>()
        override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
            requests += request
            return answer
        }
        override suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer = error("not used")
        override suspend fun models(key: String): List<String> = emptyList()
    }

    private val history = FakePatternHistory(
        listOf(
            check("a", 1, fix("esta", "está", why = "Verb estar needs an accent."), text = "Mi casa esta lejos"),
            check("b", 2, fix("tambien", "también"), text = "Yo tambien"),
            check("c", 3, fix("coger", "tomar"), text = "Voy a coger el bus"),
        ),
    )
    private val notes = Notes()
    private val client = Client(reply)
    private var key: String? = "key"
    private val lab = PatternsLab(history, notes, Preferences(), { key }, mapOf(Provider.Gemini to client), { PatternsPrompt("system") }, { 42L })

    @Test
    fun loadsAReportFromHistory() = runBlocking {
        val snapshot = lab.load()!!
        assertEquals(3, snapshot.report.checksCounted)
        assertEquals(2, snapshot.report.pattern(com.evanaronson.linguize.history.patterns.PatternKind.Accents)!!.occurrences)
        assertNull(snapshot.explanation)
    }

    @Test
    fun explainSavesTheAnswerWithItsSources() = runBlocking {
        val report = lab.load()!!.report
        val snapshot = lab.explain(report)
        val saved = notes.saved!!
        assertEquals("Most fixes are accents.", saved.summary)
        assertEquals(report.fingerprint, saved.fingerprint)
        assertEquals(PatternAnalyser.VERSION, saved.analyser)
        assertEquals(PatternsPrompt("system").version, saved.prompt)
        assertEquals("gemini", saved.provider)
        assertEquals("gemini-test", saved.model)
        assertEquals(42L, saved.createdAt)
        assertEquals(setOf("a", "b", "c"), saved.sources.toSet())
        assertEquals(ExplanationStatus.Current, snapshot.status)
        assertEquals(saved, snapshot.explanation)
    }

    @Test
    fun explainSendsGroupsAndExamplesButNotWholeMessagesOrIds() = runBlocking {
        lab.explain(lab.load()!!.report)
        val user = client.requests.single().user
        assertTrue(user.contains("\"accents\""))
        assertTrue(user.contains("está"))
        assertTrue(user.contains("Verb estar needs an accent."))
        assertFalse(user.contains("Mi casa esta lejos"))
        assertFalse(user.contains("\"a\""))
        // Fixes that form no pattern are not sent.
        assertFalse(user.contains("coger"))
        assertEquals("system", client.requests.single().system)
    }

    @Test
    fun deletingASourceCheckDeletesTheExplanation() = runBlocking {
        lab.explain(lab.load()!!.report)
        history.delete("b")
        val snapshot = lab.load()!!
        assertNull(snapshot.explanation)
        assertNull(notes.saved)
    }

    @Test
    fun clearingHistoryDeletesTheExplanation() = runBlocking {
        lab.explain(lab.load()!!.report)
        history.clear()
        lab.load()
        assertNull(notes.saved)
    }

    @Test
    fun aNewCheckLeavesTheExplanationOutdatedNotDeleted() = runBlocking {
        lab.explain(lab.load()!!.report)
        history.sessions["d"] = check("d", 4, fix("aqui", "aquí"))
        val snapshot = lab.load()!!
        assertEquals(ExplanationStatus.Outdated, snapshot.status)
        assertNotNull(notes.saved)
    }

    @Test
    fun anotherAnalyserVersionDeletesTheExplanation() = runBlocking {
        val report = lab.load()!!.report
        lab.explain(report)
        notes.saved = notes.saved!!.copy(analyser = "patterns-v0")
        assertNull(lab.reconcile(report).explanation)
        assertNull(notes.saved)
    }

    @Test
    fun watchDeletesTheExplanationWhenASourceIsDeletedElsewhere() = runBlocking {
        lab.explain(lab.load()!!.report)
        val watcher = launch(Dispatchers.Default) { lab.watch() }
        history.delete("a")
        withTimeout(5_000) { while (notes.saved != null) yield() }
        watcher.cancel()
    }

    @Test
    fun explainNeedsAKey() = runBlocking {
        key = null
        assertFalse(lab.canExplain())
        try {
            lab.explain(lab.load()!!.report)
            fail("Expected no key")
        } catch (e: CheckFailure) {
            assertEquals(CheckFailure.Reason.NoKey, e.reason)
        }
        assertNull(notes.saved)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun anInvalidReplySavesNothing() = runBlocking {
        client.answer = """{"summary":"x","tips":[{"pattern":"subjunctive","tip":"Not a group that was sent."}]}"""
        try {
            lab.explain(lab.load()!!.report)
            fail("Expected an invalid reply")
        } catch (e: CheckFailure) {
            assertEquals(CheckFailure.Reason.BadResponse, e.reason)
        }
        assertNull(notes.saved)
    }

    @Test
    fun deleteExplanationKeepsThePatterns() = runBlocking {
        lab.explain(lab.load()!!.report)
        lab.deleteExplanation()
        assertNull(notes.saved)
        assertEquals(1, lab.load()!!.report.patterns.size)
    }

    @Test
    fun statusRule() {
        val report = PatternAnalyser.analyse(history.sessions.values.toList())
        val saved = SavedExplanation("s", emptyList(), report.fingerprint, report.analyser, "p", "gemini", "m", 0, listOf("a", "b"), 3)
        assertEquals(ExplanationStatus.Current, explanationStatus(saved, report))
        assertEquals(ExplanationStatus.Outdated, explanationStatus(saved.copy(fingerprint = "other"), report))
        assertNull(explanationStatus(saved.copy(sources = listOf("a", "gone")), report))
        assertNull(explanationStatus(saved.copy(analyser = "old"), report))
    }

    @Test
    fun briefsNameGroupsInEnglishWithDistinctExamples() {
        val report = PatternAnalyser.analyse(
            listOf(check("x", 1, fix("esta", "está")), check("y", 2, fix("Esta", "Está")), check("z", 3, fix("tenia", "tenía"))),
        )
        val brief = PatternsLab.briefs(report).single()
        assertEquals("accents", brief.id)
        assertEquals("Written accents", brief.name)
        assertEquals(3, brief.times)
        assertEquals(3, brief.checks)
        assertEquals(2, brief.examples.size)
    }
}
