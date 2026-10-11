package com.evanaronson.linguize

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.history.SessionSettings
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.CheckFailure.Reason
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The checker on fake settings, keys and providers: which key and model it uses, and how it fails. */
class CheckerTest {
    private val preferences = FakePreferences()
    private val client = FakeClient { ModelAnswer(QUESTION_VERDICT, QUESTION_JSON) }
    private val keys = mutableMapOf(Provider.Gemini to "g-key", Provider.OpenAI to "o-key")
    private val prompt = Prompt(system = "instructions", schemaJson = "{}")
    private val checker = fakeChecker(preferences, client, keys, prompt)

    private fun failure(block: suspend () -> Unit): CheckFailure = try {
        runBlocking { block() }
        fail("Expected a CheckFailure")
        error("unreachable")
    } catch (e: CheckFailure) {
        e
    }

    @Test
    fun aCheckUsesTheChosenProvidersKeyAndModel() = runBlocking<Unit> {
        preferences.provider = Provider.OpenAI
        checker.check(QUESTION, null)
        assertEquals("o-key" to Provider.OpenAI.recommendedModel, client.checks.last().let { it.first to it.second })

        preferences.models[Provider.OpenAI] = "gpt-chosen"
        checker.check(QUESTION, null)
        assertEquals("gpt-chosen", client.checks.last().second)
    }

    @Test
    fun theContextIsWhatTheSettingsSayAndWhatHistoryKeeps() = runBlocking<Unit> {
        preferences.punctuation = Punctuation.Strict
        preferences.judgments = Judgments.FixOnly
        preferences.nativeLanguage = "Spanish"
        val context = checker.context()
        assertEquals(CheckContext(Provider.Gemini, Provider.Gemini.recommendedModel, Punctuation.Strict, Judgments.FixOnly, "Spanish", prompt.hash), context)
        assertEquals(
            SessionSettings("Spanish", "strict", "fix", "gemini", Provider.Gemini.recommendedModel, prompt.hash),
            context.stored,
        )
    }

    @Test
    fun aGivenContextIsUsedWhateverTheSettingsSayNow() = runBlocking<Unit> {
        val context = checker.context()
        preferences.provider = Provider.OpenAI
        preferences.punctuation = Punctuation.Casual
        val settled = listOf(Settled("tu", "informal"))
        val checked = checker.check(QUESTION, Language.Catalan, settled, context)
        val (key, model, request) = client.checks.single()
        assertEquals("g-key", key)
        assertEquals(context.model, model)
        assertEquals(Punctuation.Moderate, request.punctuation)
        assertEquals(Language.Catalan, request.language)
        assertEquals(settled, request.settled)
        assertEquals("English", request.native)
        assertSame(context, checked.context)
    }

    @Test
    fun theAnswerIsPassedOnExactlyAsItCame() = runBlocking<Unit> {
        val raw = "$QUESTION_JSON\n"
        client.answer = { ModelAnswer(QUESTION_VERDICT, raw) }
        val checked = checker.check(QUESTION, null)
        assertEquals(raw, checked.raw)
        assertTrue(checked.result is CheckResult.Reviewed)
    }

    @Test
    fun aMissingKeyFailsBeforeAnyRequest() {
        keys.remove(Provider.Gemini)
        assertEquals(Reason.NoKey, failure { checker.check(QUESTION, null) }.reason)
        assertEquals(Reason.NoKey, failure { checker.models(Provider.Gemini) }.reason)
        assertEquals(Reason.NoKey, failure { checker.testModel(Provider.Gemini, "m") }.reason)
        assertTrue(client.checks.isEmpty())
    }

    @Test
    fun aKeyWithCharactersNoKeyHasIsRefused() {
        keys[Provider.Gemini] = "abc\ndef"
        assertEquals(Reason.BadKey, failure { checker.check(QUESTION, null) }.reason)
        keys[Provider.Gemini] = "clé"
        assertEquals(Reason.BadKey, failure { checker.check(QUESTION, null) }.reason)
        assertTrue(client.checks.isEmpty())
    }

    @Test
    fun aTextOverTheLimitIsRefusedBeforeAnyRequest() {
        assertEquals(Reason.TooLong, failure { checker.check("a".repeat(Checker.MAX_CHARS + 1), null) }.reason)
        assertTrue(client.checks.isEmpty())
    }

    @Test
    fun aProvidersFailureIsPassedOnAsItIs() {
        val offline = CheckFailure(Reason.Offline, "no network")
        client.answer = { throw offline }
        assertSame(offline, failure { checker.check(QUESTION, null) })

        // One about an answer that couldn't be read keeps that answer.
        client.answer = { throw CheckFailure(Reason.BadResponse, "not json", raw = "{oops") }
        assertEquals("{oops", failure { checker.check(QUESTION, null) }.raw)
    }

    @Test
    fun anythingUnexpectedBecomesABadResponse() {
        val bug = IllegalStateException("boom")
        client.answer = { throw bug }
        val failure = failure { checker.check(QUESTION, null) }
        assertEquals(Reason.BadResponse, failure.reason)
        assertEquals("boom", failure.detail)
        assertSame(bug, failure.cause)
    }

    @Test
    fun modelsAndTestsUseTheGivenProvidersKey() = runBlocking<Unit> {
        assertEquals(listOf("m-2", "m-1"), checker.models(Provider.OpenAI))
        assertTrue(checker.testModel(Provider.OpenAI, "gpt-try") >= 0)
        assertEquals("o-key" to "gpt-try", client.checks.single().let { it.first to it.second })
    }

    @Test
    fun aKeptAnswerIsReadAgainWithoutAskingTheModel() = runBlocking<Unit> {
        val context = checker.context()
        val reused = checker.reuse(QUESTION, null, QUESTION_JSON, context)
        assertEquals(QUESTION_JSON, reused.raw)
        assertTrue(reused.result is CheckResult.Reviewed)
        assertTrue(client.checks.isEmpty())
        // One that can't be read fails as a bad response.
        val failure = failure { checker.reuse(QUESTION, null, "{", context) }
        assertEquals(Reason.BadResponse, failure.reason)
    }
}
