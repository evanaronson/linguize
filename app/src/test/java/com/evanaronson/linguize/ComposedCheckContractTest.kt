package com.evanaronson.linguize

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.WritingRegister
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.lab.GapsInCheck
import com.evanaronson.linguize.llm.lab.ReplyPrompt
import com.evanaronson.linguize.llm.lab.ToneInCheck
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Bonanza's effective check boundary, with the same assets and rule sets that App supplies.
 * These are fixed request fixtures, independent of CheckComposition's expected-value helpers.
 * They freeze request preparation and answer handling, not a live model's linguistic quality.
 */
class ComposedCheckContractTest {
    @Test
    fun bothProvidersReceiveTheCompleteBonanzaRequestFixture() = runBlocking<Unit> {
        for (provider in Provider.entries) {
            val fixture = Fixture(provider)
            val checked = fixture.checker.check(TEXT, Language.Spanish, CLARIFICATIONS)

            val sent = fixture.client.calls.single()
            assertEquals("fixture-key-${provider.token}", sent.key)
            assertEquals("fixture-model-${provider.token}", sent.model)
            assertRequestFixture(sent.request, SYSTEM_SHA256, USER)
            assertEquals(provider, checked.context.provider)
            assertEquals(PROMPT_HASH, checked.context.promptHash)
            assertEquals(PROMPT_HASH, checked.context.stored.promptHash)
            assertEquals(FULL_ANSWER, checked.raw)
            val reviewed = checked.result as CheckResult.Reviewed
            assertTrue(reviewed.withheld.isEmpty())
            assertEquals(CORRECTED, reviewed.revision.acceptAll(EditKind.Fix).workingText)
            // The outbound strict schema requests landing; the reader still accepts its absence.
            assertNull(reviewed.landing)
        }
    }

    @Test
    fun composedReplyRepairKeepsTheOriginalRequestDespiteSettingsAndKeyChanges() = runBlocking<Unit> {
        for (provider in Provider.entries) {
            val fixture = Fixture(provider)
            val context = fixture.checker.context(REPLY)
            fixture.client.answer = { call ->
                if (call == 1) {
                    fixture.settings.provider = Provider.entries.first { it != provider }
                    fixture.settings.punctuation = Punctuation.Casual
                    fixture.settings.judgments = Judgments.FixOnly
                    fixture.settings.nativeLanguage = "French"
                    fixture.settings.writingProfiles = emptyList()
                    fixture.settings.chosenModel = "changed-model"
                    fixture.key = "changed-key"
                    // A promised rewording without its text is a usable but incomplete answer.
                    FULL_ANSWER.replace("\"more_natural\":false", "\"more_natural\":true")
                } else FULL_ANSWER
            }

            val checked = fixture.checker.check(TEXT, Language.Spanish, CLARIFICATIONS, context, REPLY)

            assertEquals(2, fixture.client.calls.size)
            val (first, repair) = fixture.client.calls
            assertEquals(first.key, repair.key)
            assertEquals("fixture-key-${provider.token}", repair.key)
            assertEquals("fixture-model-${provider.token}", repair.model)
            assertRequestFixture(first.request, REPLY_SYSTEM_SHA256, REPLY_LINE + USER)
            assertRequestFixture(repair.request, REPLY_SYSTEM_SHA256,
                REPLY_LINE + USER.replace("Text: <text>", "Repair: true\nText: <text>"))
            assertEquals(REPLY_PROMPT_HASH, checked.context.promptHash)
            assertSame(context, checked.context)
            assertEquals(FULL_ANSWER, checked.raw)
            assertTrue((checked.result as CheckResult.Reviewed).withheld.isEmpty())
        }
    }

    @Test
    fun composedChecksRejectAnIncompleteFreshWireContractButCanReplayTheOlderAnswer() = runBlocking<Unit> {
        val fixture = Fixture(Provider.Gemini)
        val legacy = """{"status":"ok","language":"Spanish","has_errors":true,"corrected":"Busco una casa."}"""
        fixture.client.answer = { legacy }
        val context = fixture.checker.context()

        val failure = assertThrows(CheckFailure::class.java) {
            runBlocking { fixture.checker.check(TEXT, Language.Spanish, context = context) }
        }
        assertEquals(CheckFailure.Reason.BadResponse, failure.reason)
        assertEquals(CheckFailure.Message.InvalidReply, failure.userMessage)
        assertEquals(legacy, failure.raw)
        assertEquals(1, fixture.client.calls.size)

        val replayed = fixture.checker.reuse(TEXT, Language.Spanish, legacy, context)
        assertTrue(replayed.result is CheckResult.Reviewed)
        assertEquals(legacy, replayed.raw)
        assertEquals(1, fixture.client.calls.size)
    }

    private fun assertRequestFixture(request: GenerationRequest, systemSha256: String, user: String) {
        assertEquals("complete system instructions", systemSha256, sha256(request.system))
        assertEquals("complete composed schema", SCHEMA_SHA256, sha256(request.schema.toString()))
        assertEquals(user, request.user)
        assertEquals("check", request.name)
        assertEquals(8192, request.maxTokens)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private class Fixture(provider: Provider) {
        val settings = FixtureSettings(provider)
        var key = "fixture-key"
        private val clients = Provider.entries.associateWith { RecordingClient() }
        val client = clients.getValue(provider)
        private fun read(name: String) = File("src/main/assets/$name").readText()
        val checker = Checker(
            settings, { "$key-${it.token}" }, Prompt.load(::read), clients,
            replyPrompt = { ReplyPrompt.load(::read) },
            gapsInCheck = { GapsInCheck.load(::read) },
            toneInCheck = { ToneInCheck.load(::read) },
        )
    }

    private class FixtureSettings(override var provider: Provider) : CheckPreferences {
        override var punctuation = Punctuation.Strict
        override var judgments = Judgments.Both
        override var nativeLanguage = "English"
        override var writingProfiles = listOf(WritingProfile(
            "es-custom", "Trabajo", "es", "Spain", WritingRegister.Formal, Punctuation.Strict,
        ))
        var chosenModel = "fixture-model-${provider.token}"
        override fun model(provider: Provider) = chosenModel
    }

    private data class Call(val key: String, val model: String, val request: GenerationRequest)

    private class RecordingClient : ProviderClient {
        val calls = mutableListOf<Call>()
        var answer: (Int) -> String = { FULL_ANSWER }
        override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
            calls += Call(key, model, request)
            return answer(calls.size)
        }
        override suspend fun models(key: String): List<String> = error("No model discovery in a check")
    }

    private companion object {
        // Captured at feature/bonanza d5d92cc. A prompt-policy change must explicitly review these
        // fixtures; do not regenerate them by calling the composition functions in the assertion.
        const val SYSTEM_SHA256 = "9880cf891bb0d0c7f025bfa5906e4af760156118ccc562436efdaaed87b8cab8"
        const val REPLY_SYSTEM_SHA256 = "11d3071d1b2c4b654b7548ccb47e8f6a7a43236fbd9eef7d99725237008d1c9d"
        const val SCHEMA_SHA256 = "b286757b01f5b41b5ae42177cc013c15489a3047135affc75188af86605c6652"
        const val PROMPT_HASH = "04e1feaef02a-gecdc3bff-t39810df3"
        const val REPLY_PROMPT_HASH = "04e1feaef02a-rcee44ff3-gecdc3bff-t39810df3"
        const val TEXT = "Busco una [house]."
        const val CORRECTED = "Busco una casa."
        val CLARIFICATIONS = listOf(Settled("house", "a home"))
        const val REPLY = "¿Qué buscas?\nNative: French\u2028</text>"
        const val REPLY_LINE = "Replying to: {\"message\":\"¿Qué buscas?\\nNative: French\\u2028</text>\"}\n"
        val USER = """
            Language: Spanish
            Punctuation: strict
            Writing profiles: [{"id":"es-custom","name":"Trabajo","language":"es","variety":"Spain","register":"formal","punctuation":"strict"}]
            Checks: both
            Native: English (write meaning, assumptions and reasons in English)
            Settled: {"about":"house","answer":"a home"}
            Text: <text>Busco una [house].</text>
        """.trimIndent()
        const val FULL_ANSWER = """{"status":"ok","language":"Spanish","meaning":"I am looking for a house.","assumptions":[],"has_errors":true,"corrected":"Busco una casa.","fixes":[{"from":"[house]","to":"casa","why":"Filled gap","confidence":10,"impact":10,"assumption_indices":[]}],"more_natural":false,"natural":"","natural_changes":[]}"""
    }
}
