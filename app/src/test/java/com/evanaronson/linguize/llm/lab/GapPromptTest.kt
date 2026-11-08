package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.GapFiller
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.WritingRegister
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.FakeHttp
import com.evanaronson.linguize.llm.GeminiClient
import com.evanaronson.linguize.llm.OpenAIClient
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GapPromptTest {
    /** The shipped assets, as the app reads them. */
    private val prompt = GapPrompt.load { File("src/main/assets/$it").readText() }
    private val source = "Hola, ¿puedes [pick me up] a las 8? Tengo que [drop off] las llaves."
    private val raw = """{"language":"Spanish","fills":[
        {"original":"[pick me up]","filled":"recogerme","note":"Clitic joined to the infinitive.","alternatives":["venir a buscarme"]},
        {"original":"[drop off]","filled":"dejar","note":"","alternatives":[]}
    ]}"""

    @Test fun theShippedSchemaIsStrictAndTheRequestCarriesProfileGapsAndMessageAsData() {
        assertEquals("false", prompt.schema["additionalProperties"]!!.jsonPrimitive.content)
        val formal = WritingProfile("p", "Spanish", "es", "Mexican", WritingRegister.Formal, com.evanaronson.linguize.core.Punctuation.Strict)
        val catalan = WritingProfile.defaultFor(Language.Catalan)
        val request = prompt.request(source, Language.Spanish, "English", listOf(catalan, formal))
        assertEquals("fill_gaps", request.name)
        assertTrue(request.system.contains("Leave the writer's own words alone"))
        val user = Json.parseToJsonElement(request.user).jsonObject
        assertEquals("Spanish", user["language"]!!.jsonPrimitive.content)
        assertEquals(source, user["message"]!!.jsonPrimitive.content)
        assertEquals(listOf("pick me up", "drop off"), user["markedGaps"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Only the target language's profile.
        val profiles = user["profiles"]!!.jsonArray
        assertEquals(1, profiles.size)
        assertEquals("formal", profiles[0].jsonObject["register"]!!.jsonPrimitive.content)
        assertEquals("Mexican", profiles[0].jsonObject["variety"]!!.jsonPrimitive.content)
        // Detecting the language sends every profile.
        val detect = Json.parseToJsonElement(prompt.request(source, null, "English", listOf(catalan, formal)).user).jsonObject
        assertEquals("detect", detect["language"]!!.jsonPrimitive.content)
        assertEquals(2, detect["profiles"]!!.jsonArray.size)
    }

    @Test fun anAnswerIsPlacedOnTheSource() {
        val fill = prompt.read(raw, source)
        assertEquals("Hola, ¿puedes recogerme a las 8? Tengo que dejar las llaves.", fill.text)
        assertEquals("Clitic joined to the infinitive.", fill.fills[0].note)
        assertEquals(listOf("recogerme", "venir a buscarme"), fill.fills[0].options)
    }

    @Test fun unusableAnswersAreInvalidReplies() {
        val bad = listOf(
            "not json",
            """{"language":"Spanish"}""",
            """{"language":"Spanish","fills":[],"extra":1}""",
            // Leaves a marked gap unfilled.
            """{"language":"Spanish","fills":[{"original":"[pick me up]","filled":"recogerme","note":"","alternatives":[]}]}""",
            // Quotes words that are not in the message.
            """{"language":"Spanish","fills":[{"original":"puede [pick me up]","filled":"recogerme","note":"","alternatives":[]},
                {"original":"[drop off]","filled":"dejar","note":"","alternatives":[]}]}""",
        )
        for (answer in bad) {
            val failure = assertThrows(CheckFailure::class.java) { prompt.read(answer, source) }
            assertEquals(CheckFailure.Reason.BadResponse, failure.reason)
            assertEquals(CheckFailure.Message.InvalidReply, failure.userMessage)
        }
    }

    @Test fun bothProvidersSendTheFillRequestAndNotTheCheckPrompt() = runBlocking {
        val checkPrompt = Prompt("CORRECTION PROMPT MUST NOT BE SENT", "{}")
        for (provider in Provider.entries) {
            val wire = JsonPrimitive(raw)
            val payload = if (provider == Provider.Gemini) {
                """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":$wire}]}}]}"""
            } else {
                """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":$wire}]}]}"""
            }
            val http = FakeHttp { _, _ -> 200 to payload }
            val client = if (provider == Provider.Gemini) GeminiClient(http.client, checkPrompt) else OpenAIClient(http.client, checkPrompt)
            val prefs = FakePreferences().apply { this.provider = provider; models[provider] = "test-model" }
            val filler = GapFiller(prefs, ProviderKeys { "key" }, mapOf(provider to client)) { prompt }
            val fill = filler.fill(source, Language.Spanish)
            assertEquals("Hola, ¿puedes recogerme a las 8? Tengo que dejar las llaves.", fill.text)
            val body = http.body(0)
            assertFalse(body.contains("CORRECTION PROMPT MUST NOT BE SENT"))
            assertTrue(body.contains("markedGaps"))
            assertTrue(http.requests.single().url.toString().contains("test-model") || body.contains("test-model"))
        }
    }

    @Test fun missingKeyAndOverlongTextFailBeforeAnyRequest() = runBlocking {
        val http = FakeHttp { _, _ -> error("No request expected") }
        val clients = mapOf(Provider.Gemini to GeminiClient(http.client, Prompt("x", "{}")))
        val noKey = GapFiller(FakePreferences(), ProviderKeys { null }, clients) { prompt }
        assertEquals(CheckFailure.Reason.NoKey, runCatching { noKey.fill(source, null) }.exceptionOrNull().let { (it as CheckFailure).reason })
        val filler = GapFiller(FakePreferences(), ProviderKeys { "key" }, clients) { prompt }
        assertEquals(CheckFailure.Reason.TooLong,
            runCatching { filler.fill("a".repeat(3001), null) }.exceptionOrNull().let { (it as CheckFailure).reason })
        assertTrue(http.requests.isEmpty())
    }
}
