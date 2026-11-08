package com.evanaronson.linguize

import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.FakeHttp
import com.evanaronson.linguize.llm.GeminiClient
import com.evanaronson.linguize.llm.OpenAIClient
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.reply.ReplyPrompt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ReplyDrafterTest {
    private val checkPrompt = Prompt("CORRECTION PROMPT MUST NOT BE SENT", "{}")
    private val replyPrompt = ReplyPrompt.load { File("src/main/assets/$it").readText() }
    private val intents = """{"language":"Spanish","intents":[{"label":"Accept","instruction":"Say yes."},{"label":"Decline politely","instruction":"Say no, thanks."}]}"""
    private val reply = """{"language":"Spanish","sentences":[{"text":"¡Sí, claro!","gloss":"Yes, of course!","lineBreak":false}],"words":[{"word":"claro","meaning":"of course"}]}"""

    private fun payload(provider: Provider, raw: String): String {
        val wire = JsonPrimitive(raw)
        return if (provider == Provider.Gemini) {
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":$wire}]}}]}"""
        } else {
            """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":$wire}]}]}"""
        }
    }

    @Test fun bothProvidersSendTheReplyContractAndReadItsAnswers() = runBlocking {
        for (provider in Provider.entries) {
            val answers = listOf(intents, reply)
            val http = FakeHttp { _, index -> 200 to payload(provider, answers[index]) }
            val client = if (provider == Provider.Gemini) GeminiClient(http.client, checkPrompt) else OpenAIClient(http.client, checkPrompt)
            val prefs = FakePreferences().apply {
                this.provider = provider
                nativeLanguage = "Catalan"
                models[provider] = "chosen-model"
            }
            val drafter = ReplyDrafter(prefs, ProviderKeys { "key" }, mapOf(provider to client)) { replyPrompt }

            val suggested = drafter.intents("¿Vienes el sábado?", "Vens dissabte?")
            assertEquals(listOf("Accept", "Decline politely"), suggested.intents.map { it.label })
            val draft = drafter.draft("¿Vienes el sábado?", "Vens dissabte?", "Digues que sí", simplerThan = "Por supuesto que iré")
            assertEquals("¡Sí, claro!", draft.text)
            assertEquals("Yes, of course!", draft.sentences.single().gloss)

            for (index in 0..1) {
                val body = http.body(index)
                assertFalse(body.contains("CORRECTION PROMPT MUST NOT BE SENT"))
                assertTrue(body.contains("learning a language reply"))
                assertTrue(http.requests[index].url.toString().contains("chosen-model") || body.contains("chosen-model"))
            }
            val second = http.body(1)
            assertTrue(second.contains("sentences"))
            assertTrue(second.contains("Digues que sí"))
            assertTrue(second.contains("simplerThan"))
            assertTrue(second.contains("Catalan"))
        }
    }

    @Test fun noKeyAndOverlongInputFailBeforeAnythingIsSent() = runBlocking {
        val http = FakeHttp { _, _ -> error("nothing should be sent") }
        val client = GeminiClient(http.client, checkPrompt)
        val noKey = ReplyDrafter(FakePreferences(), ProviderKeys { null }, mapOf(Provider.Gemini to client)) { replyPrompt }
        assertReason(CheckFailure.Reason.NoKey) { noKey.intents("Hola", "Hi") }
        val drafter = ReplyDrafter(FakePreferences(), ProviderKeys { "key" }, mapOf(Provider.Gemini to client)) { replyPrompt }
        assertReason(CheckFailure.Reason.TooLong) { drafter.draft("Hola", "Hi", " ") }
        assertReason(CheckFailure.Reason.TooLong) { drafter.draft("Hola", "Hi", "x".repeat(ReplyPrompt.MAX_INTENT_INPUT + 1)) }
        assertReason(CheckFailure.Reason.TooLong) { drafter.intents("x".repeat(Checker.MAX_CHARS + 1), "") }
        assertTrue(http.requests.isEmpty())
    }

    @Test fun aMalformedAnswerIsABadResponse() = runBlocking {
        val http = FakeHttp { _, _ -> 200 to payload(Provider.Gemini, """{"language":"Spanish","sentences":[],"words":[]}""") }
        val drafter = ReplyDrafter(FakePreferences(), ProviderKeys { "key" }, mapOf(Provider.Gemini to GeminiClient(http.client, checkPrompt))) { replyPrompt }
        assertReason(CheckFailure.Reason.BadResponse) { drafter.draft("Hola", "Hi", "Say hi") }
        val sent = Json.parseToJsonElement(http.body(0)).jsonObject
        val user = sent.getValue("contents").jsonArray[0].jsonObject.getValue("parts").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content
        assertEquals("reply", Json.parseToJsonElement(user).jsonObject.getValue("task").jsonPrimitive.content)
    }

    private suspend fun assertReason(reason: CheckFailure.Reason, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: CheckFailure) {
            assertEquals(reason, e.reason)
        }
    }
}
