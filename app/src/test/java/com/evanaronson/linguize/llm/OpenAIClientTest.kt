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

class OpenAIClientTest {
    private val prompt = Prompt(system = "", schemaJson = "{}")
    private val request = CheckRequest("Bon dia", null, Punctuation.Moderate, Judgments.Both)

    @Test
    fun openAIErrorsMapToWhatTheCardSays() {
        val cases = listOf(
            401 to """{"error":{"message":"Incorrect API key provided","code":"invalid_api_key"}}""" to Reason.BadKey,
            404 to """{"error":{"message":"The model does not exist","code":"model_not_found"}}""" to Reason.BadModel,
            403 to """{"error":{"message":"Project does not have access to model gpt-x","code":"model_not_found"}}""" to Reason.BadModel,
            403 to """{"error":{"message":"Country not supported","code":"unsupported_country_region_territory"}}""" to Reason.BadKey,
            400 to """{"error":{"message":"Unsupported value","param":"model","code":null}}""" to Reason.BadModel,
            400 to """{"error":{"message":"Invalid schema","param":"text.format.schema","code":null}}""" to Reason.BadResponse,
            429 to """{"error":{"message":"Slow down","code":"rate_limit_exceeded"}}""" to Reason.RateLimited,
        )
        for ((call, expected) in cases) {
            val (code, payload) = call
            assertEquals("$code $payload", expected, OpenAIClient.failure(code, payload).reason)
        }
    }

    @Test
    fun anAnswerCutOffAtTheTokenLimitIsTooLong() {
        val payload = """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},""" +
            """"output":[{"type":"message","content":[{"type":"output_text","text":"{\"status\":\"ok\",\"mea"}]}]}"""
        val failure = assertThrows(CheckFailure::class.java) { OpenAIClient.answer(payload) }
        assertEquals(Reason.TooLong, failure.reason)
    }

    @Test
    fun anAnswerStoppedForAnotherReasonIsABadResponse() {
        val payload = """{"status":"incomplete","incomplete_details":{"reason":"content_filter"},"output":[]}"""
        val failure = assertThrows(CheckFailure::class.java) { OpenAIClient.answer(payload) }
        assertEquals(Reason.BadResponse, failure.reason)
        assertEquals("OpenAI declined to check this text.", failure.detail)
    }

    @Test
    fun aModelWithoutReasoningControlIsAskedAgainWithItsDefault() {
        val http = FakeHttp { _, index ->
            if (index == 0) 400 to """{"error":{"message":"Unsupported value: 'reasoning.effort'","param":"reasoning.effort"}}"""
            else 200 to answer(VERDICT_JSON)
        }
        runBlocking { OpenAIClient(http.client, prompt).check("key", "gpt-x", request) }
        assertEquals(2, http.requests.size)
        assertTrue("\"reasoning\"" in http.body(0))
        assertFalse("\"reasoning\"" in http.body(1))
        assertTrue("\"max_output_tokens\":${Prompt.MAX_OUTPUT_TOKENS}" in http.body(1))
    }

    private fun answer(text: String) =
        """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":${JsonPrimitive(text)}}]}]}"""
}
