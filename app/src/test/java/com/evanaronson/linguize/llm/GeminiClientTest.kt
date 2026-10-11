package com.evanaronson.linguize.llm

import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.llm.CheckFailure.Reason
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiClientTest {
    private val prompt = Prompt(system = "", schemaJson = "{}")
    private val request = CheckRequest("Bon dia", null, Punctuation.Moderate, Judgments.Both)

    @Test
    fun geminiErrorsMapToWhatTheCardSays() {
        val cases = listOf(
            400 to """{"error":{"message":"API key not valid","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""" to Reason.BadKey,
            403 to """{"error":{"message":"Permission denied"}}""" to Reason.BadKey,
            403 to """{"error":{"message":"This model is not available to you"}}""" to Reason.BadModel,
            400 to """{"error":{"message":"responseJsonSchema is not supported by this model"}}""" to Reason.BadModel,
            404 to """{"error":{"message":"models/x is not found"}}""" to Reason.BadModel,
            429 to """{"error":{"message":"Slow down"}}""" to Reason.RateLimited,
        )
        for ((call, expected) in cases) {
            val (code, payload) = call
            assertEquals("$code $payload", expected, GeminiClient.failure(code, payload).reason)
        }
    }

    /** A key restricted from the models list is a key problem; "ModelService" in the message isn't a model. */
    @Test
    fun aBlockedMethodIsNotABadModel() {
        val payload = """{"error":{"code":403,"message":"Requests to this API generativelanguage.googleapis.com method """ +
            """google.ai.generativelanguage.v1beta.ModelService.ListModels are blocked.","details":[{"metadata":{"model":"x"}}]}}"""
        assertEquals(Reason.BadKey, GeminiClient.failure(403, payload).reason)
    }

    @Test
    fun anAnswerCutOffAtTheTokenLimitIsTooLong() {
        val payload = """{"candidates":[{"content":{"parts":[{"text":"{\"status\":\"ok\",\"mean"}]},"finishReason":"MAX_TOKENS"}]}"""
        val failure = assertThrows(CheckFailure::class.java) { GeminiClient.answer(payload) }
        assertEquals(Reason.TooLong, failure.reason)
    }

    @Test
    fun aSafetyStopIsSaidPlainly() {
        val payload = """{"candidates":[{"finishReason":"SAFETY"}]}"""
        val failure = assertThrows(CheckFailure::class.java) { GeminiClient.answer(payload) }
        assertEquals("Gemini declined to check this text.", failure.detail)
        val blocked = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        assertEquals("Gemini declined to check this text.", assertThrows(CheckFailure::class.java) { GeminiClient.answer(blocked) }.detail)
    }

    @Test
    fun thoughtsAreLeftOutOfTheAnswer() {
        val payload = """{"candidates":[{"content":{"parts":[{"text":"hmm","thought":true},{"text":"{}"}]},"finishReason":"STOP"}]}"""
        assertEquals("{}", GeminiClient.answer(payload))
    }

    @Test
    fun aModelWithoutMinimalThinkingIsAskedAgainWithItsDefault() {
        val http = FakeHttp { _, index ->
            if (index == 0) 400 to """{"error":{"message":"Thinking level MINIMAL is not supported"}}"""
            else 200 to answer(VERDICT_JSON)
        }
        val client = GeminiClient(http.client, prompt)
        runBlocking { client.check("key", "gemini-x", request) }
        assertEquals(2, http.requests.size)
        assertTrue("thinkingConfig" in http.body(0))
        assertFalse("thinkingConfig" in http.body(1))

        // Remembered, so the next check goes straight to the default.
        runBlocking { client.check("key", "gemini-x", request) }
        assertFalse("thinkingConfig" in http.body(2))
    }

    @Test
    fun theOutputLimitIsSent() {
        val http = FakeHttp { _, _ -> 200 to answer(VERDICT_JSON) }
        runBlocking { GeminiClient(http.client, prompt).check("key", "gemini-x", request) }
        assertTrue("\"maxOutputTokens\":${Prompt.MAX_OUTPUT_TOKENS}" in http.body(0))
    }

    @Test
    fun modelPagesAreFollowedUntilThereIsNoNextToken() {
        val http = FakeHttp { _, index ->
            200 to modelsPage("gemini-$index-flash", next = if (index < 2) "t$index" else null)
        }
        val ids = runBlocking { GeminiClient(http.client, prompt).models("key") }
        assertEquals(listOf("gemini-2-flash", "gemini-1-flash", "gemini-0-flash"), ids)
        assertEquals(listOf(null, "t0", "t1"), http.requests.map { it.url.queryParameter("pageToken") })
    }

    @Test
    fun aRepeatedPageTokenEndsTheModelList() {
        val http = FakeHttp { _, index -> 200 to modelsPage("gemini-$index-flash", next = "same") }
        val ids = runBlocking { GeminiClient(http.client, prompt).models("key") }
        assertEquals(2, http.requests.size)
        assertEquals(listOf("gemini-1-flash", "gemini-0-flash"), ids)
    }

    @Test
    fun modelPagesStopAtTheCap() {
        val http = FakeHttp { _, index -> 200 to modelsPage("gemini-$index-flash", next = "t$index") }
        val ids = runBlocking { GeminiClient(http.client, prompt).models("key") }
        assertEquals(GeminiClient.MAX_MODEL_PAGES, http.requests.size)
        assertEquals(GeminiClient.MAX_MODEL_PAGES, ids.size)
    }

    private fun modelsPage(id: String, next: String?): String {
        val token = next?.let { ""","nextPageToken":"$it"""" }.orEmpty()
        return """{"models":[{"name":"models/$id","supportedGenerationMethods":["generateContent"]}]$token}"""
    }

    private fun answer(text: String) =
        """{"candidates":[{"content":{"parts":[{"text":${JsonPrimitive(text)}}]},"finishReason":"STOP"}]}"""
}
