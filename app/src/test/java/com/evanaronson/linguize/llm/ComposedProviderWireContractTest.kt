package com.evanaronson.linguize.llm

import com.evanaronson.linguize.llm.lab.PreparedCheckFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Real provider serializers with an intercepted transport: no HTTP call reaches the network. */
class ComposedProviderWireContractTest {
    @Test
    fun geminiWireBytesPreserveEveryFrozenPreparedRequest() = runBlocking {
        val http = FakeHttp { _, _ -> 200 to geminiAnswer() }
        val client = GeminiClient(http.client, PreparedCheckFixtures.base)
        for ((index, fixture) in PreparedCheckFixtures.cases.withIndex()) {
            val prepared = fixture.prepare()
            fixture.assertGeneration(prepared.generation)
            assertEquals(VERDICT_JSON, client.generate("fixture-key", "gemini-fixture-model", prepared.generation))
            val body = http.body(index)
            assertEquals(fixture.id, fixture.expected.string("geminiBodySha256"), PreparedCheckFixtures.sha256(body))
            val fields = Json.parseToJsonElement(body).jsonObject
            val config = fields.getValue("generationConfig").jsonObject
            assertEquals(fixture.id, fixture.expected.string("schemaSha256"),
                PreparedCheckFixtures.sha256(config.getValue("responseJsonSchema").toString()))
            assertEquals(8192, config.getValue("maxOutputTokens").jsonPrimitive.int)
            assertEquals("fixture-key", http.requests[index].header("x-goog-api-key"))
        }
    }

    @Test
    fun openAiWireBytesPreserveEveryFrozenRequestAndRemoveOnlyTopLevelPropertyOrdering() = runBlocking {
        val http = FakeHttp { _, _ -> 200 to openAiAnswer() }
        val client = OpenAIClient(http.client, PreparedCheckFixtures.base)
        for ((index, fixture) in PreparedCheckFixtures.cases.withIndex()) {
            val prepared = fixture.prepare()
            fixture.assertGeneration(prepared.generation)
            assertEquals(VERDICT_JSON, client.generate("fixture-key", "gpt-6.1-sol", prepared.generation))
            val body = http.body(index)
            assertEquals(fixture.id, fixture.expected.string("openAiBodySha256"), PreparedCheckFixtures.sha256(body))
            val fields = Json.parseToJsonElement(body).jsonObject
            val format = fields.getValue("text").jsonObject.getValue("format").jsonObject
            val schema = format.getValue("schema").jsonObject
            assertEquals(fixture.id, fixture.expected.string("openAiSchemaSha256"), PreparedCheckFixtures.sha256(schema.toString()))
            assertEquals(fixture.id, JsonObject(prepared.generation.schema - "propertyOrdering"), schema)
            assertFalse(schema.containsKey("propertyOrdering"))
            assertEquals("check", format.string("name"))
            assertEquals(8192, fields.getValue("max_output_tokens").jsonPrimitive.int)
            assertEquals("Bearer fixture-key", http.requests[index].header("Authorization"))
        }
    }

    @Test
    fun optionFallbackRetainsTheComposedContractAndIsRememberedForTheNextRequest() = runBlocking {
        val fixture = PreparedCheckFixtures.cases.single { it.id == "gaps-tone-reply-repair" }
        val generation = fixture.prepare().generation
        for (provider in Provider.entries) {
            val option = if (provider == Provider.Gemini) "thinkingConfig" else "reasoning"
            val http = FakeHttp { _, index ->
                if (index == 0) 400 to """{"error":{"message":"unsupported $option"}}"""
                else 200 to if (provider == Provider.Gemini) geminiAnswer() else openAiAnswer()
            }
            val client = if (provider == Provider.Gemini) GeminiClient(http.client, PreparedCheckFixtures.base)
                else OpenAIClient(http.client, PreparedCheckFixtures.base)
            val model = if (provider == Provider.Gemini) "gemini-fixture-model" else "gpt-6.1-sol"
            assertEquals(VERDICT_JSON, client.generate("fixture-key", model, generation))
            assertEquals(VERDICT_JSON, client.generate("fixture-key", model, generation))
            assertEquals(3, http.requests.size)
            val first = Json.parseToJsonElement(http.body(0)).jsonObject
            val withoutOption = if (provider == Provider.Gemini) {
                JsonObject(first + ("generationConfig" to JsonObject(first.getValue("generationConfig").jsonObject - option)))
            } else JsonObject(first - option)
            assertEquals(provider.name, withoutOption.toString(), http.body(1))
            assertEquals(provider.name, http.body(1), http.body(2))
        }
    }

    private fun geminiAnswer() =
        """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":${JsonPrimitive(VERDICT_JSON)}}]}}]}"""

    private fun openAiAnswer() =
        """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":${JsonPrimitive(VERDICT_JSON)}}]}]}"""

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
