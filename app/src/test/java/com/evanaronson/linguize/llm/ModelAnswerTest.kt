package com.evanaronson.linguize.llm

import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Verdict
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The model's answer travels with what was read from it, and stays when it can't be read. */
class ModelAnswerTest {
    private val prompt = Prompt(system = "", schemaJson = "{}")

    @Test
    fun theAnswerIsKeptExactlyAsItCame() {
        // A field the app doesn't know yet is dropped from the verdict, never from the raw answer.
        val raw = VERDICT_JSON.replace("\"status\":\"ok\"", "\"status\":\"ok\",\"tone\":\"warm\"")
        val answer = prompt.read(raw)
        assertEquals(raw, answer.raw)
        assertEquals(Verdict.Status.Ok, answer.verdict.status)
    }

    @Test
    fun anAnswerThatCantBeReadIsKeptWithTheFailure() {
        val failure = assertThrows(CheckFailure::class.java) { prompt.read("{\"status\":") }
        assertEquals(CheckFailure.Reason.BadResponse, failure.reason)
        assertEquals("{\"status\":", failure.raw)
    }

    @Test
    fun aClientReturnsTheRawAnswer() {
        val http = FakeHttp { _, _ ->
            200 to """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":${JsonPrimitive(VERDICT_JSON)}}]}]}"""
        }
        val request = CheckRequest("x", null, Punctuation.Moderate, Judgments.Both)
        val answer = runBlocking { OpenAIClient(http.client, prompt).check("key", "gpt-x", request) }
        assertEquals(VERDICT_JSON, answer.raw)
    }
}
