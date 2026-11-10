package com.evanaronson.linguize

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.lab.ReplyPrompt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

/** Lab: a check with a message to reply to goes through the reply prompt; one without is the shipped check. */
class ReplyCheckerTest {
    private val prompt = Prompt(system = "instructions", schemaJson = """{"required":["status"]}""")
    private val reply = ReplyPrompt("reply rules")
    private val client = RecordingClient()
    private val checker = Checker(
        FakePreferences(),
        { "key" },
        prompt,
        Provider.entries.associateWith { client },
        replyPrompt = { reply },
    )

    @Test
    fun withoutAMessageTheShippedRequestIsGeneratedUnchanged() = runBlocking<Unit> {
        val checked = checker.check(QUESTION, null)
        assertEquals(1, client.generations.size)
        val sent = client.generations.single()
        assertEquals(prompt.system, sent.system)
        assertEquals(prompt.schema, sent.schema)
        assertTrue(sent.user.startsWith("Language: auto\n"))
        assertTrue(sent.user.endsWith("Text: <text>$QUESTION</text>"))
        assertEquals(prompt.hash, checked.context.promptHash)
        assertEquals(prompt.hash, checker.context().promptHash)
        assertEquals(prompt.hash, checker.context(null).promptHash)
    }

    @Test
    fun withAMessageTheComposedRequestIsSentAndReadWithTheShippedSchema() = runBlocking<Unit> {
        val message = "Com estàs?"
        val context = checker.context(message)
        assertEquals(reply.hash(prompt, message), context.promptHash)
        val checked = checker.check(QUESTION, null, context = context, replyingTo = message)

        assertEquals(1, client.generations.size)
        val sent = client.generations.single()
        assertEquals(reply.system(prompt.system), sent.system)
        assertTrue(sent.user.startsWith(ReplyPrompt.LINE_PREFIX + "{\"message\":\"Com estàs?\"}\n"))
        assertTrue(sent.user.endsWith("Text: <text>$QUESTION</text>"))
        assertEquals(prompt.schema, sent.schema)
        assertEquals("check", sent.name)
        val reviewed = checked.result as CheckResult.Reviewed
        assertEquals(1, reviewed.revision.edits.size)
        assertTrue(reviewed.withheld.isEmpty())
        assertEquals(FIXED_JSON, checked.raw)
    }

    @Test
    fun theRepairRetryKeepsTheContext() = runBlocking<Unit> {
        // A rewording promised but not given: the checker asks once more, with Repair.
        client.answers += listOf("{\"status\":\"ok\",\"has_errors\":true,\"corrected\":\"Com estàs?\",\"more_natural\":true}", FIXED_JSON)
        val checked = checker.check(QUESTION, null, replyingTo = "Hola")
        assertEquals(2, client.generations.size)
        assertTrue(client.generations.all { it.user.startsWith(ReplyPrompt.LINE_PREFIX + "{\"message\":\"Hola\"}\n") })
        assertTrue("Repair: true" in client.generations.last().user)
        assertEquals(FIXED_JSON, checked.raw)
    }

    @Test
    fun aReplyAnswerMissingRequiredFieldsFailsAsAnyFreshAnswerDoes() = runBlocking<Unit> {
        val bad = "{\"has_errors\":false}"
        client.answers += bad
        try {
            checker.check(QUESTION, null, replyingTo = "Hola")
            fail("Expected a CheckFailure")
        } catch (e: CheckFailure) {
            assertEquals(CheckFailure.Reason.BadResponse, e.reason)
            assertEquals(bad, e.raw)
        }
    }

    @Test
    fun contextsWithAndWithoutAMessageDiffer() = runBlocking<Unit> {
        assertNotEquals(checker.context(), checker.context("Hola"))
        assertNotEquals(checker.context("Hola"), checker.context("Adéu"))
        assertEquals(checker.context("Hola"), checker.context("Hola"))
    }

    private companion object {
        const val FIXED_JSON = "{\"status\":\"ok\",\"language\":\"Catalan\",\"meaning\":\"How are you?\",\"assumptions\":[]," +
            "\"has_errors\":true,\"corrected\":\"Com estàs?\",\"fixes\":[{\"from\":\"estas\",\"to\":\"estàs\",\"why\":\"Accent\"," +
            "\"confidence\":10,\"impact\":3,\"assumption_indices\":[]}],\"more_natural\":false,\"natural\":\"\",\"natural_changes\":[]}"
    }

    private class RecordingClient : ProviderClient {
        val generations: MutableList<GenerationRequest> = Collections.synchronizedList(mutableListOf())
        val answers: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
            generations += request
            return if (answers.isEmpty()) FIXED_JSON else answers.removeAt(0)
        }

        override suspend fun models(key: String): List<String> = emptyList()
    }
}
