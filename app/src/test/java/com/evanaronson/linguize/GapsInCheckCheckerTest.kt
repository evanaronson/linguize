package com.evanaronson.linguize

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.lab.GapsInCheck
import com.evanaronson.linguize.llm.lab.ReplyPrompt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * Lab: every check carries the gap rules, and a filled gap comes back as an ordinary fix that
 * the shipped parser and edit engine show, accept, undo and apply like any other.
 */
class GapsInCheckCheckerTest {
    private val prompt = Prompt.load { File("src/main/assets/$it").readText() }
    private val gaps = GapsInCheck.load { File("src/main/assets/$it").readText() }
    private val reply = ReplyPrompt.load { File("src/main/assets/$it").readText() }
    private val preferences = FakePreferences()
    private val client = RecordingClient()
    private val checker = Checker(
        preferences, { "key" }, prompt, Provider.entries.associateWith { client },
        replyPrompt = { reply }, gapsInCheck = { gaps },
    )

    @Test
    fun everyCheckSendsTheShippedPromptWithTheGapRulesAndTheShippedUserMessage() = runBlocking<Unit> {
        val checked = checker.check(TEXT, null)
        assertTrue(client.checks.isEmpty())
        val sent = client.generations.single()
        assertEquals(gaps.system(prompt), sent.system)
        assertTrue(sent.system.startsWith(File("src/main/assets/check_prompt.md").readText().trimEnd()))
        val request = CheckRequest(TEXT, null, preferences.punctuation, preferences.judgments, native = "English",
            writingProfiles = preferences.writingProfiles.distinctBy { it.language }.sortedBy { it.language })
        assertEquals(prompt.userMessage(request), sent.user)
        assertEquals(prompt.schema, sent.schema)
        assertEquals("check", sent.name)
        assertEquals(gaps.hash(prompt.hash), checked.context.promptHash)
        assertEquals(gaps.hash(prompt.hash), checker.context().promptHash)
    }

    @Test
    fun aBracketFillIsAFixTheEditEngineAppliesAndUndoes() = runBlocking<Unit> {
        val reviewed = checker.check(TEXT, null).result as CheckResult.Reviewed
        assertTrue(reviewed.withheld.isEmpty())
        val fixes = reviewed.revision.edits(EditKind.Fix)
        assertEquals(listOf("[pick me up]", "drop off", "a [the market]"), fixes.map { it.from })
        assertEquals(listOf("recogerme", "dejar", "al mercado"), fixes.map { it.replacement })
        assertEquals("Filled gap", fixes.first().why)

        val one = reviewed.revision.accept(fixes.first().id)
        assertEquals("¿Puedes recogerme a las 8? Tengo que drop off las llaves y ir a [the market].", one.workingText)
        assertEquals(TEXT, one.undo().workingText)
        assertEquals(FILLED, reviewed.revision.acceptAll(EditKind.Fix).workingText)
        assertEquals("Can you pick me up at 8? I have to drop off the keys and go to the market.", reviewed.meaning)
    }

    @Test
    fun withARewordingOnlyCheckTheFillsComeAsRewordings() = runBlocking<Unit> {
        preferences.judgments = Judgments.NaturalizeOnly
        client.answers += NATURAL_JSON
        val reviewed = checker.check(TEXT, null).result as CheckResult.Reviewed
        assertTrue(reviewed.revision.edits(EditKind.Fix).isEmpty())
        assertEquals(FILLED, reviewed.revision.acceptAll(EditKind.Natural).workingText)
    }

    @Test
    fun aReplyCheckCarriesBothRuleSetsAndKeepsThemApartInTheHash() = runBlocking<Unit> {
        val context = checker.context("¿A qué hora?")
        checker.check(TEXT, null, context = context, replyingTo = "¿A qué hora?")
        val sent = client.generations.single()
        assertEquals(reply.system(gaps.prompt(prompt)), sent.system)
        assertTrue(sent.system.indexOf(gaps.addendum.trim()) < sent.system.indexOf(reply.addendum.trim()))
        assertTrue(sent.user.startsWith(ReplyPrompt.LINE_PREFIX))
        assertEquals(gaps.hash(reply.hash(prompt, "¿A qué hora?")), context.promptHash)
    }

    @Test
    fun withoutTheRulesTheShippedCheckRunsAsBefore() = runBlocking<Unit> {
        val plain = Checker(preferences, { "key" }, prompt, Provider.entries.associateWith { client })
        plain.check(TEXT, null)
        // The fake shipped answer withholds its changes, so a repair retry follows: both go the shipped way.
        assertTrue(client.checks.isNotEmpty())
        assertTrue(client.generations.isEmpty())
        assertEquals(prompt.hash, plain.context().promptHash)
    }

    private companion object {
        const val TEXT = "¿Puedes [pick me up] a las 8? Tengo que drop off las llaves y ir a [the market]."
        const val FILLED = "¿Puedes recogerme a las 8? Tengo que dejar las llaves y ir al mercado."
        const val CHANGES = "[" +
            "{\"from\":\"[pick me up]\",\"to\":\"recogerme\",\"why\":\"Filled gap\",\"confidence\":9,\"impact\":10,\"assumption_indices\":[]}," +
            "{\"from\":\"drop off\",\"to\":\"dejar\",\"why\":\"Written in English\",\"confidence\":9,\"impact\":10,\"assumption_indices\":[]}," +
            "{\"from\":\"a [the market]\",\"to\":\"al mercado\",\"why\":\"Filled gap\",\"confidence\":10,\"impact\":10,\"assumption_indices\":[]}" +
            "]"
        const val MEANING = "Can you pick me up at 8? I have to drop off the keys and go to the market."
        const val FIXED_JSON = "{\"status\":\"ok\",\"language\":\"Spanish\",\"meaning\":\"$MEANING\",\"assumptions\":[]," +
            "\"has_errors\":true,\"corrected\":\"$FILLED\",\"fixes\":$CHANGES,\"more_natural\":false,\"natural\":\"\",\"natural_changes\":[]}"
        const val NATURAL_JSON = "{\"status\":\"ok\",\"language\":\"Spanish\",\"meaning\":\"$MEANING\",\"assumptions\":[]," +
            "\"has_errors\":false,\"corrected\":\"\",\"fixes\":[],\"more_natural\":true,\"natural\":\"$FILLED\",\"natural_changes\":$CHANGES}"
    }

    private class RecordingClient : ProviderClient {
        val checks: MutableList<CheckRequest> = Collections.synchronizedList(mutableListOf())
        val generations: MutableList<GenerationRequest> = Collections.synchronizedList(mutableListOf())
        val answers: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer {
            checks += request
            return ModelAnswer(QUESTION_VERDICT, QUESTION_JSON)
        }

        override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
            generations += request
            return if (answers.isEmpty()) FIXED_JSON else answers.removeAt(0)
        }

        override suspend fun models(key: String): List<String> = emptyList()
    }
}
