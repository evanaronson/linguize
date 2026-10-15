package com.evanaronson.linguize

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.history.SessionSettings
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.CheckFailure.Reason
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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
        keys[Provider.Gemini] = "a key"
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
    fun cancellationIsPassedToTheCallerInsteadOfDisplayedAsAnError() {
        val cancelled = CancellationException("check replaced")
        client.answer = { throw cancelled }
        val failure = assertThrows(CancellationException::class.java) { runBlocking { checker.check(QUESTION, null) } }
        assertEquals("check replaced", failure.message)
    }

    @Test
    fun oversizedAnswersFromAnyProviderAreRefusedBeforeInterpretation() {
        client.answer = { ModelAnswer(QUESTION_VERDICT.copy(corrected = "x".repeat(Prompt.MAX_REWRITE_CHARS + 1)), "oversized") }
        val failure = failure { checker.check(QUESTION, null) }
        assertEquals(Reason.BadResponse, failure.reason)
        assertEquals(CheckFailure.Message.ReplyTooLarge, failure.userMessage)
        assertEquals("oversized", failure.raw)
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
        assertEquals("{", failure.raw)
        assertEquals(CheckFailure.Message.InvalidReply, failure.userMessage)
    }

    private val partial = QUESTION_VERDICT.copy(moreNatural = true)
    private fun answer(verdict: Verdict) = ModelAnswer(verdict, Json.encodeToString(verdict))

    @Test
    fun anIncompleteAnswerRetriesOnceWithTheSameProviderModelAndSettings() = runBlocking<Unit> {
        for (provider in Provider.entries) {
            client.checks.clear()
            preferences.provider = provider
            val context = checker.context()
            val originalKey = keys.getValue(provider)
            client.answer = { request ->
                if (!request.repair) {
                    preferences.provider = Provider.entries.first { it != provider }
                    preferences.nativeLanguage = "Spanish"
                    keys[provider] = "changed-key"
                    answer(partial)
                } else answer(QUESTION_VERDICT)
            }
            val checked = checker.check(QUESTION, null, listOf(Settled("Who", "Me")), context)
            assertEquals(2, client.checks.size)
            val first = client.checks[0]
            val second = client.checks[1]
            assertEquals(originalKey to context.model, second.first to second.second)
            assertEquals(first.third.copy(repair = true), second.third)
            assertTrue(!first.third.repair)
            assertSame(context, checked.context)
            assertEquals(answer(QUESTION_VERDICT).raw, checked.raw)
            assertTrue((checked.result as CheckResult.Reviewed).withheld.isEmpty())
        }
    }

    @Test
    fun aRepeatedPartialAnswerStopsAfterTwoCallsAndKeepsItsRawEvidence() = runBlocking<Unit> {
        client.answer = { answer(partial).copy(raw = if (it.repair) "second" else "first") }
        val checked = checker.check(QUESTION, null)
        assertEquals(2, client.checks.size)
        assertEquals("first", checked.raw)
        assertTrue((checked.result as CheckResult.Reviewed).withheld.isNotEmpty())
    }

    @Test
    fun retryOnlyReplacesPartialEvidenceWhenItResolvesKnownIncompleteness() = runBlocking<Unit> {
        val bothMissing = partial.copy(corrected = "")
        val fixesMissing = QUESTION_VERDICT.copy(corrected = "")
        for ((first, second, chosen) in listOf(
            Triple(bothMissing, partial, partial),
            Triple(partial, bothMissing, partial),
            Triple(partial, fixesMissing, partial),
            Triple(partial, Verdict(Verdict.Status.Unclear), partial),
            Triple(partial, Verdict(Verdict.Status.WrongLanguage), partial),
        )) {
            client.checks.clear()
            client.answer = { answer(if (it.repair) second else first) }
            val checked = checker.check(QUESTION, null)
            assertEquals(2, client.checks.size)
            assertEquals(answer(chosen).raw, checked.raw)
        }
    }

    @Test
    fun aFailedRetryKeepsTheUsableFirstAnswer() = runBlocking<Unit> {
        for (error in listOf(CheckFailure(Reason.Offline), IllegalStateException("bad retry"))) {
            client.checks.clear()
            client.answer = { if (it.repair) throw error else answer(partial) }
            assertEquals(answer(partial).raw, checker.check(QUESTION, null).raw)
            assertEquals(2, client.checks.size)
        }
        client.answer = { answer(if (it.repair) partial.copy(corrected = "x".repeat(Prompt.MAX_REWRITE_CHARS + 1)) else partial) }
        assertEquals(answer(partial).raw, checker.check(QUESTION, null).raw)
    }

    @Test
    fun cancellingTheRetryStillCancelsTheCheck() {
        client.answer = { if (it.repair) throw CancellationException("retry cancelled") else answer(partial) }
        assertThrows(CancellationException::class.java) { runBlocking { checker.check(QUESTION, null) } }
        assertEquals(2, client.checks.size)
    }

    @Test
    fun completeUnclearWrongLanguageAndFailedFirstChecksDoNotRetry() = runBlocking<Unit> {
        for (verdict in listOf(QUESTION_VERDICT, Verdict(Verdict.Status.Ok),
            Verdict(Verdict.Status.Unclear), Verdict(Verdict.Status.WrongLanguage))) {
            client.checks.clear()
            client.answer = { answer(verdict) }
            checker.check(QUESTION, Language.Catalan)
            assertEquals(1, client.checks.size)
        }
        client.checks.clear()
        client.answer = { throw CheckFailure(Reason.Offline) }
        failure { checker.check(QUESTION, null) }
        assertEquals(1, client.checks.size)
    }

    @Test
    fun reusingAnIncompleteAnswerNeverRetries() = runBlocking<Unit> {
        val saved = answer(partial)
        val checked = checker.reuse(QUESTION, null, saved.raw, checker.context())
        assertEquals(saved.raw, checked.raw)
        assertTrue((checked.result as CheckResult.Reviewed).withheld.isNotEmpty())
        assertTrue(client.checks.isEmpty())
    }
}
