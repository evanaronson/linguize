package com.evanaronson.linguize

import com.evanaronson.linguize.core.lab.LandingRisk
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.lab.LandingPrompt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LandingCheckerTest {
    private val raw = """{"variety":"Catalan (central)","summary":"Friendly.","risk":"fine","warmth":4,"directness":3,
        "formality":2,"drivers":[],"notes":[],"versions":[]}"""

    private class Generating(val reply: (GenerationRequest) -> String) : ProviderClient {
        val sent = mutableListOf<Triple<String, String, GenerationRequest>>()
        override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
            sent += Triple(key, model, request)
            return reply(request)
        }
        override suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer = error("Not a check")
        override suspend fun models(key: String) = emptyList<String>()
    }

    private fun prompt() = LandingPrompt.load { File("src/main/assets/$it").readText() }

    @Test fun usesTheChosenProviderModelKeyAndNativeLanguage() = runBlocking {
        val prefs = FakePreferences().apply {
            provider = Provider.OpenAI
            models[Provider.OpenAI] = "picked-model"
            nativeLanguage = "Spanish"
        }
        val client = Generating { raw }
        val engine = LandingChecker(prefs, ProviderKeys { "key-${it.name}" }, mapOf(Provider.OpenAI to client), ::prompt)
        val landing = engine.landing("  Bon dia, com va?  ")
        assertEquals(LandingRisk.Fine, landing.risk)
        val (key, model, request) = client.sent.single()
        assertEquals("key-OpenAI", key)
        assertEquals("picked-model", model)
        assertTrue(request.user.contains("\"explainIn\":\"Spanish\""))
        assertTrue("The message is sent trimmed", request.user.contains("\"message\":\"Bon dia, com va?\""))
    }

    @Test fun missingOrUnusableKeysAndBlankTextFailBeforeAnyRequest() = runBlocking {
        val client = Generating { raw }
        val clients = mapOf<Provider, ProviderClient>(Provider.Gemini to client)
        fun failure(keys: ProviderKeys, text: String = "Hola") = try {
            runBlocking { LandingChecker(FakePreferences(), keys, clients, ::prompt).landing(text) }
            null
        } catch (e: CheckFailure) {
            e.reason
        }
        assertEquals(CheckFailure.Reason.NoKey, failure(ProviderKeys { null }))
        assertEquals(CheckFailure.Reason.BadKey, failure(ProviderKeys { "has space" }))
        assertEquals(CheckFailure.Reason.TooLong, failure(ProviderKeys { "key" }, "   "))
        assertEquals(CheckFailure.Reason.TooLong, failure(ProviderKeys { "key" }, "a".repeat(Checker.MAX_CHARS + 1)))
        assertTrue(client.sent.isEmpty())
    }

    @Test fun aTransportOrReadingProblemIsABadResponse() = runBlocking {
        val engine = LandingChecker(FakePreferences(), ProviderKeys { "key" },
            mapOf(Provider.Gemini to Generating { throw IllegalStateException("boom") }), ::prompt)
        val failure = try { engine.landing("Hola"); null } catch (e: CheckFailure) { e }
        assertEquals(CheckFailure.Reason.BadResponse, failure?.reason)
    }
}
