package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.LanguageBridge
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.WritingRegister
import com.evanaronson.linguize.core.languagebridge.Bridged
import com.evanaronson.linguize.data.CheckPreferences
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LanguageBridgeTransportTest {
    private val checkPrompt = Prompt("CORRECTION PROMPT MUST NOT BE SENT", "{}")
    private val bridgePrompt = LanguageBridgePrompt.load { File("src/main/assets/$it").readText() }
    private val raw = """{"status":"ok","source_language":"Spanish","text":"Et trobo a faltar","pairs":[""" +
        """{"source":"Te echo de menos","target":"Et trobo a faltar","kind":"different","note":"Catalan says trobar a faltar."}]}"""

    /** Settings whose Catalan profile is casual, unlike the default. */
    private class Prefs(
        override val provider: Provider = Provider.Gemini,
        override val nativeLanguage: String = "English",
    ) : CheckPreferences {
        override val punctuation = Punctuation.Moderate
        override val judgments = Judgments.Both
        override fun model(provider: Provider) = "test-model"
        override val writingProfiles = listOf(
            WritingProfile.defaultFor(Language.Catalan, Punctuation.Strict).copy(register = WritingRegister.Casual),
            WritingProfile.defaultFor(Language.Spanish),
        )
    }

    private fun payload(provider: Provider): String {
        val wire = JsonPrimitive(raw)
        return if (provider == Provider.Gemini) {
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":$wire}]}}]}"""
        } else {
            """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":$wire}]}]}"""
        }
    }

    @Test fun bothProvidersSendTheBridgeContractWithTheTargetProfile() = runBlocking {
        for (provider in Provider.entries) {
            val http = FakeHttp { _, _ -> 200 to payload(provider) }
            val client = if (provider == Provider.Gemini) GeminiClient(http.client, checkPrompt) else OpenAIClient(http.client, checkPrompt)
            val prefs = Prefs(provider)
            val bridge = LanguageBridge(prefs, ProviderKeys { "key" }, mapOf(provider to client)) { bridgePrompt }
            val result = bridge.bridge("Te echo de menos", "ca", sourceHint = "es")
            assertEquals(Bridged.Status.Ok, result.status)
            assertEquals("Et trobo a faltar", result.text)
            assertEquals("Catalan says trobar a faltar.", result.pairs.single().note)
            val body = http.body(0)
            assertFalse(body.contains("CORRECTION PROMPT MUST NOT BE SENT"))
            assertTrue(body.contains("language_bridge") || body.contains("source_language"))
            assertTrue(body.contains("test-model") || http.requests.single().url.toString().contains("test-model"))
            Json.parseToJsonElement(body).jsonObject
            // The user message names the casual, strict Catalan profile.
            assertTrue(body.contains("\\\"register\\\":\\\"casual\\\""))
            assertTrue(body.contains("\\\"punctuation\\\":\\\"strict\\\""))
            assertEquals(1, http.requests.size)
        }
    }

    @Test fun aPinnedSourceInTheTargetLanguageIsNotSentAsAHint() = runBlocking {
        val http = FakeHttp { _, _ -> 200 to payload(Provider.Gemini) }
        val bridge = LanguageBridge(Prefs(), ProviderKeys { "key" }, mapOf(Provider.Gemini to GeminiClient(http.client, checkPrompt))) { bridgePrompt }
        bridge.bridge("Te echo de menos", "ca", sourceHint = "ca")
        val user = Json.parseToJsonElement(http.body(0)).jsonObject.getValue("contents").toString()
        assertTrue(user.contains("\\\"sourceHint\\\":null"))
    }

    @Test fun missingKeysAndOverlongTextFailBeforeAnyRequest() = runBlocking {
        val http = FakeHttp { _, _ -> error("No request expected") }
        val clients = mapOf(Provider.Gemini to GeminiClient(http.client, checkPrompt))
        val noKey = LanguageBridge(Prefs(), ProviderKeys { null }, clients) { bridgePrompt }
        val failure = runCatching { noKey.bridge("Hola", "ca") }.exceptionOrNull() as CheckFailure
        assertEquals(CheckFailure.Reason.NoKey, failure.reason)
        val withKey = LanguageBridge(Prefs(), ProviderKeys { "key" }, clients) { bridgePrompt }
        val tooLong = runCatching { withKey.bridge("a".repeat(100_000), "ca") }.exceptionOrNull() as CheckFailure
        assertEquals(CheckFailure.Reason.TooLong, tooLong.reason)
        val blank = runCatching { withKey.bridge("   ", "ca") }.exceptionOrNull() as CheckFailure
        assertEquals(CheckFailure.Reason.TooLong, blank.reason)
        assertTrue(http.requests.isEmpty())
        Unit
    }

    @Test fun targetsAreTheProfileLanguages() = runBlocking {
        val bridge = LanguageBridge(Prefs(), ProviderKeys { "key" }, emptyMap()) { bridgePrompt }
        assertEquals(listOf("ca", "es"), bridge.targets().map { it.language })
        assertEquals("Catalan", Json.parseToJsonElement(
            bridgePrompt.request("x", bridge.targets().first(), null, "English").user,
        ).jsonObject.getValue("target").jsonObject.getValue("language").jsonPrimitive.content)
    }
}
