package com.evanaronson.linguize

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.lab.LandingOverall
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.lab.CheckComposition
import com.evanaronson.linguize.llm.lab.GapsInCheck
import com.evanaronson.linguize.llm.lab.ReplyPrompt
import com.evanaronson.linguize.llm.lab.ToneInCheck
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * Lab: How it lands is part of the one check. The request carries the tone rules and the
 * `landing` schema; the answer is an ordinary check with an optional reading; tone help comes
 * back as rewordings the edit engine accepts and undoes like any other.
 */
class ToneInCheckCheckerTest {
    private fun read(name: String) = File("src/main/assets/$name").readText()
    private val prompt = Prompt.load(::read)
    private val gaps = GapsInCheck.load(::read)
    private val tone = ToneInCheck.load(::read)
    private val reply = ReplyPrompt.load(::read)
    private val client = RecordingClient()
    private val checker = Checker(
        FakePreferences(), { "key" }, prompt, Provider.entries.associateWith { client },
        replyPrompt = { reply }, gapsInCheck = { gaps }, toneInCheck = { tone },
    )

    @Test
    fun everyCheckSendsOneComposedRequestAndReadsTheLanding() = runBlocking<Unit> {
        client.answers += answer(LANDING)
        val checked = checker.check(TEXT, null)
        val sent = client.generations.single()
        assertEquals(1, client.generations.size)
        assertEquals(tone.system(gaps.system(prompt.system)), sent.system)
        assertEquals(tone.schema(prompt.schema), sent.schema)
        assertEquals("check", sent.name)
        assertEquals(CheckComposition.hash(prompt, gaps, tone), checked.context.promptHash)

        val reviewed = checked.result as CheckResult.Reviewed
        val landing = reviewed.landing!!
        assertEquals(LandingOverall.CouldLandDifferently, landing.overall)
        assertEquals(CORRECTED, landing.text)
        assertEquals(listOf("Quiero que"), landing.drivers)
    }

    @Test
    fun anAnswerWithoutALandingIsStillACheck() = runBlocking<Unit> {
        client.answers += answer(null)
        val reviewed = checker.check(TEXT, null).result as CheckResult.Reviewed
        assertNull(reviewed.landing)
        assertEquals(1, reviewed.revision.edits(EditKind.Fix).size)
    }

    @Test
    fun aBadLandingNeverCostsTheCheck() = runBlocking<Unit> {
        client.answers += answer("{\"overall\":42}")
        val reviewed = checker.check(TEXT, null).result as CheckResult.Reviewed
        assertNull(reviewed.landing)
        assertEquals(CORRECTED, reviewed.revision.acceptAll(EditKind.Fix).workingText)
    }

    @Test
    fun toneHelpIsAnOrdinaryRewordingTheEditEngineAcceptsAndUndoes() = runBlocking<Unit> {
        client.answers += answer(LANDING, natural = true)
        val reviewed = checker.check(TEXT, null).result as CheckResult.Reviewed
        val rewordings = reviewed.revision.edits(EditKind.Natural)
        assertEquals(listOf("Softer request"), rewordings.map { it.why })
        val accepted = reviewed.revision.acceptAll(EditKind.Fix).accept(rewordings.single().id)
        assertEquals(NATURAL, accepted.workingText)
        assertEquals(CORRECTED, accepted.undo().workingText)
    }

    @Test
    fun aKeptAnswerShowsItsLandingAgainAndAnOlderOneHasNone() = runBlocking<Unit> {
        val context = checker.context()
        assertEquals(LandingOverall.CouldLandDifferently,
            (checker.reuse(TEXT, null, answer(LANDING), context).result as CheckResult.Reviewed).landing?.overall)
        assertNull((checker.reuse(TEXT, null, answer(null), context).result as CheckResult.Reviewed).landing)
    }

    @Test
    fun aChatCheckCarriesAllThreeInOrderAndKeepsThemApartInTheHash() = runBlocking<Unit> {
        client.answers += answer(LANDING)
        val context = checker.context("¿Me lo mandas?")
        val checked = checker.check(TEXT, null, context = context, replyingTo = "¿Me lo mandas?")
        val sent = client.generations.single()
        assertEquals(reply.system(tone.system(gaps.system(prompt.system))), sent.system)
        assertTrue(sent.system.indexOf(gaps.addendum.trim()) < sent.system.indexOf(tone.addendum.trim()))
        assertTrue(sent.system.indexOf(tone.addendum.trim()) < sent.system.indexOf(reply.addendum.trim()))
        assertTrue(sent.user.startsWith(ReplyPrompt.LINE_PREFIX))
        assertEquals(tone.schema(prompt.schema), sent.schema)
        assertEquals(CheckComposition.hash(prompt, gaps, tone, reply.hash(prompt, "¿Me lo mandas?")), context.promptHash)
        assertTrue((checked.result as CheckResult.Reviewed).landing != null)
    }

    private fun answer(landing: String?, natural: Boolean = false): String {
        val naturals = if (natural) "\"more_natural\":true,\"natural\":\"$NATURAL\",\"natural_changes\":[{\"from\":\"contrato.\"," +
            "\"to\":\"contrato, por favor.\",\"why\":\"Softer request\",\"confidence\":7,\"impact\":7,\"assumption_indices\":[]}]"
        else "\"more_natural\":false,\"natural\":\"\",\"natural_changes\":[]"
        return "{\"status\":\"ok\",\"language\":\"Spanish\",\"meaning\":\"I want you to send me the contract.\",\"assumptions\":[]," +
            "\"has_errors\":true,\"corrected\":\"$CORRECTED\",\"fixes\":[{\"from\":\"envies\",\"to\":\"envíes\",\"why\":\"Accent\"," +
            "\"confidence\":10,\"impact\":3,\"assumption_indices\":[]}],$naturals${landing?.let { ",\"landing\":$it" }.orEmpty()}}"
    }

    private companion object {
        const val TEXT = "Quiero que me envies el contrato."
        const val CORRECTED = "Quiero que me envíes el contrato."
        const val NATURAL = "Quiero que me envíes el contrato, por favor."
        const val LANDING = "{\"overall\":\"could_land_differently\",\"summary\":\"a bit demanding\",\"warmth\":2,\"directness\":5," +
            "\"formality\":3,\"drivers\":[\"Quiero que\"],\"notes\":[]}"
    }

    private class RecordingClient : ProviderClient {
        val generations: MutableList<GenerationRequest> = Collections.synchronizedList(mutableListOf())
        val answers: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
            generations += request
            return answers.removeAt(0)
        }

        override suspend fun models(key: String): List<String> = emptyList()
    }
}
