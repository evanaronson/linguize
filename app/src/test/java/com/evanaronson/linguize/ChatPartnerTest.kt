package com.evanaronson.linguize

import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.memory.MemoryMessage
import com.evanaronson.linguize.core.memory.UserMemory
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.FakeHttp
import com.evanaronson.linguize.llm.GeminiClient
import com.evanaronson.linguize.llm.OpenAIClient
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.chat.ChatPrompt
import com.evanaronson.linguize.llm.chat.Turn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The partner goes through the same clients as checks, with its own contract and the writer's memory. */
class ChatPartnerTest {
    private val checkPrompt = Prompt("CORRECTION PROMPT MUST NOT BE SENT", "{}")
    private val chatPrompt = ChatPrompt.load { File("src/main/assets/$it").readText() }
    private val memory = object : UserMemory {
        override suspend fun forPrompt() = "Has a dog called Pipa."
        override suspend fun learnFrom(source: String, messages: List<MemoryMessage>) = Unit
        override suspend fun forget(source: String) = Unit
    }

    private fun payload(provider: Provider, raw: String): String {
        val wire = JsonPrimitive(raw)
        return if (provider == Provider.Gemini) {
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":$wire}]}}]}"""
        } else {
            """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":$wire}]}]}"""
        }
    }

    @Test fun bothProvidersSendTheChatContractWithMemoryAndReadTheReply() = runBlocking {
        for (provider in Provider.entries) {
            val answers = listOf("""{"reply":"Hola! Com està la Pipa?","title":""}""", """{"reply":"Que bé!","title":"La Pipa"}""")
            val http = FakeHttp { _, index -> 200 to payload(provider, answers[index]) }
            val client = if (provider == Provider.Gemini) GeminiClient(http.client, checkPrompt) else OpenAIClient(http.client, checkPrompt)
            val prefs = FakePreferences().apply { this.provider = provider }
            val partner = ChatPartner(prefs, ProviderKeys { "key" }, mapOf(provider to client), { memory }) { chatPrompt }

            assertEquals("Hola! Com està la Pipa?", partner.open(Language.Catalan))
            val reply = partner.reply(Language.Catalan, listOf(Turn(false, "Hola!"), Turn(true, "Molt bé, gràcies")), needTitle = true)
            assertEquals("Que bé!", reply.reply)
            assertEquals("La Pipa", reply.title)
            for (index in 0..1) {
                val body = http.body(index)
                assertFalse(body.contains("CORRECTION PROMPT MUST NOT BE SENT"))
                assertTrue(body.contains("Never comment on the learner"))
                assertTrue(body.contains("Has a dog called Pipa."))
            }
            assertTrue(http.body(1).contains("Molt bé, gràcies"))
        }
    }

    @Test fun noKeyFailsBeforeAnythingIsSent() = runBlocking {
        val http = FakeHttp { _, _ -> error("nothing should be sent") }
        val partner = ChatPartner(FakePreferences(), ProviderKeys { null }, mapOf(Provider.Gemini to GeminiClient(http.client, checkPrompt)),
            { memory }) { chatPrompt }
        assertFalse(partner.hasKey())
        try {
            partner.open(Language.Spanish)
            fail("Expected no key")
        } catch (e: CheckFailure) {
            assertEquals(CheckFailure.Reason.NoKey, e.reason)
        }
        assertTrue(http.requests.isEmpty())
    }
}
