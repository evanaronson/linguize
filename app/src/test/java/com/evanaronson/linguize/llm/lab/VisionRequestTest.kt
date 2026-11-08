package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.FakeHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

class VisionRequestTest {
    private val prompt = PhotoPrompt.load { File("src/main/assets/$it").readText() }
    private val image = ImageInput("image/jpeg", byteArrayOf(-1, -40, -1, -32, 1, 2, 3))
    private val request = prompt.request(image, "English")
    private val answer = """{"legible":true,"language":"Catalan","transcription":"Bon dia","summary":"A greeting.",""" +
        """"translation":"Good morning","actions":[],"words":[]}"""

    private fun parse(body: String) = Json.parseToJsonElement(body).jsonObject

    @Test fun geminiSendsTheImageAsInlineDataBesideTheTextAndTheReadingSchema() = runBlocking {
        val wire = JsonPrimitive(answer)
        val http = FakeHttp { _, _ -> 200 to """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":$wire}]}}]}""" }
        val raw = GeminiVisionClient(http.client).generate("g-key", "gemini-test", request)
        assertEquals(answer, raw)

        val sent = http.requests.single()
        assertTrue(sent.url.toString().endsWith("/models/gemini-test:generateContent"))
        assertEquals("g-key", sent.header("x-goog-api-key"))
        val body = parse(http.body(0))
        val parts = body["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray.map { it.jsonObject }
        val inline = parts.first()["inlineData"]!!.jsonObject
        assertEquals("image/jpeg", inline["mimeType"]!!.jsonPrimitive.content)
        assertEquals(image.bytes.toList(), Base64.getDecoder().decode(inline["data"]!!.jsonPrimitive.content).toList())
        assertTrue(parts[1]["text"]!!.jsonPrimitive.content.contains("\"explainIn\":\"English\""))
        val system = body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(system.contains("transcription"))
        val config = body["generationConfig"]!!.jsonObject
        assertEquals(prompt.schema, config["responseJsonSchema"])
        assertEquals("LOW", config["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
    }

    @Test fun geminiRetriesWithoutThinkingWhenTheModelRefusesIt() = runBlocking {
        val wire = JsonPrimitive(answer)
        val http = FakeHttp { _, index ->
            if (index == 0) 400 to """{"error":{"message":"Thinking level is not supported"}}"""
            else 200 to """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":$wire}]}}]}"""
        }
        GeminiVisionClient(http.client).generate("g-key", "gemini-old", request)
        assertEquals(2, http.requests.size)
        assertNull(parse(http.body(1))["generationConfig"]!!.jsonObject["thinkingConfig"])
    }

    @Test fun openAiSendsTheImageAsADataUrlWithAStrictSchema() = runBlocking {
        val wire = JsonPrimitive(answer)
        val http = FakeHttp { _, _ ->
            200 to """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":$wire}]}]}"""
        }
        val raw = OpenAIVisionClient(http.client).generate("o-key", "gpt-test", request)
        assertEquals(answer, raw)

        val sent = http.requests.single()
        assertTrue(sent.url.toString().endsWith("/v1/responses"))
        assertEquals("Bearer o-key", sent.header("Authorization"))
        val body = parse(http.body(0))
        assertEquals("gpt-test", body["model"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        val content = body["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("input_text", content[0]["type"]!!.jsonPrimitive.content)
        assertEquals("input_image", content[1]["type"]!!.jsonPrimitive.content)
        val url = content[1]["image_url"]!!.jsonPrimitive.content
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        assertEquals(image.bytes.toList(), Base64.getDecoder().decode(url.substringAfter(",")).toList())
        val format = body["text"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertEquals("true", format["strict"]!!.jsonPrimitive.content)
        // OpenAI rejects Gemini's ordering hint.
        assertFalse((format["schema"] as JsonObject).containsKey("propertyOrdering"))
    }

    @Test fun providerErrorsBecomeTheSameFailuresAsChecks() = runBlocking {
        val http = FakeHttp { _, _ -> 401 to """{"error":{"code":"invalid_api_key","message":"Incorrect API key"}}""" }
        try {
            OpenAIVisionClient(http.client).generate("bad", "gpt-test", request)
            fail()
        } catch (e: CheckFailure) {
            assertEquals(CheckFailure.Reason.BadKey, e.reason)
        }
        val gemini = FakeHttp { _, _ -> 200 to """{"candidates":[{"finishReason":"SAFETY"}]}""" }
        try {
            GeminiVisionClient(gemini.client).generate("g-key", "gemini-test", request)
            fail()
        } catch (e: CheckFailure) {
            assertEquals(CheckFailure.Message.Declined, e.userMessage)
        }
    }

    @Test fun theSchemaAsksForEveryFieldTheReadingHas() {
        val required = prompt.schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("legible", "language", "transcription", "summary", "translation", "actions", "words"), required)
        // Strict structured output needs every property required and no extras, at every level.
        val properties = prompt.schema["properties"]!!.jsonObject
        assertEquals(required, properties.keys)
        for (name in listOf("actions", "words")) {
            val item = properties[name]!!.jsonObject["items"]!!.jsonObject
            assertEquals(item["properties"]!!.jsonObject.keys, item["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertEquals("false", item["additionalProperties"]!!.jsonPrimitive.content)
        }
    }

    @Test fun theImageIsNeverPrintedIntoLogs() {
        assertEquals("ImageInput(image/jpeg, 7 bytes)", image.toString())
        assertFalse(request.toString().contains(image.base64()))
    }
}
