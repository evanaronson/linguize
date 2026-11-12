package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.PreparedCheck
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.VERDICT_JSON
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Independent pre-extraction goldens, also used to check the actual Kotlin export from Python. */
class PreparedCheckContractTest {
    @Test
    fun everyCompositionAndRepairMatchesTheReviewedPreExtractionRequest() {
        assertEquals(24, PreparedCheckFixtures.cases.size)
        for (fixture in PreparedCheckFixtures.cases) {
            val prepared = fixture.prepare()
            fixture.assertGeneration(prepared.generation)
            assertEquals(fixture.id, fixture.expected.string("compatibilityHash"), prepared.compatibilityHash)
            assertEquals(fixture.id, fixture.expected.string("parserPolicy"), prepared.parserPolicy)
            assertEquals(fixture.id, fixture.expected.string("baseReaderHash"), prepared.baseReaderHash)
        }
    }

    @Test
    fun repairChangesOnlyItsSettingLineAcrossAllEightCompositions() {
        val matrix = PreparedCheckFixtures.cases.take(16).chunked(2)
        assertEquals(8, matrix.size)
        for ((initialFixture, repairFixture) in matrix) {
            val initial = initialFixture.prepare()
            val repair = repairFixture.prepare()
            assertEquals(initialFixture.id, initial.generation.copy(
                user = initial.generation.user.replace("\nText: <text>", "\nRepair: true\nText: <text>"),
            ), repair.generation)
            assertEquals(initialFixture.id, initial.compatibilityHash, repair.compatibilityHash)
            assertEquals(initialFixture.id, initial.parserPolicy, repair.parserPolicy)
            assertEquals(initialFixture.id, initial.baseReaderHash, repair.baseReaderHash)
        }
    }

    @Test
    fun everyCompositionUsesTheBaseFreshReaderAndPreservesFailedRawEvidence() {
        val legacy = """{"status":"ok","language":"Catalan","has_errors":false}"""
        val malformedOptionalLanding = VERDICT_JSON.dropLast(1) + """, "landing":["not a landing"]}"""
        for (fixture in PreparedCheckFixtures.cases.take(16).filterNot { it.request.repair }) {
            val prepared = fixture.prepare()
            // The outbound tone schema requires landing, but this reader intentionally does not.
            assertEquals(fixture.id, VERDICT_JSON, prepared.read(VERDICT_JSON).raw)
            assertEquals(fixture.id, malformedOptionalLanding, prepared.read(malformedOptionalLanding).raw)
            val failure = assertThrows(CheckFailure::class.java) { prepared.read(legacy) }
            assertEquals(fixture.id, CheckFailure.Message.InvalidReply, failure.userMessage)
            assertEquals(fixture.id, legacy, failure.raw)
            // Replay has a different policy; the extraction must not silently strengthen it.
            assertEquals("Catalan", PreparedCheckFixtures.base.parseVerdict(legacy).language)
        }
    }
}

/** Only test inputs are decoded here; expected request bytes never call a composition helper. */
internal object PreparedCheckFixtures {
    private fun asset(name: String) = File("src/main/assets/$name").readText()
    val base = Prompt.load(::asset)
    private val gaps = GapsInCheck.load(::asset)
    private val tone = ToneInCheck.load(::asset)
    private val reply = ReplyPrompt.load(::asset)
    private val frozen = Json.parseToJsonElement(
        File("../eval/fixtures/prepared_checks.json").readText(),
    ).jsonObject
    val cases = frozen.getValue("cases").jsonArray.map { element ->
        val fixture = element.jsonObject
        val input = fixture.getValue("request").jsonObject
        val languageCode = input.getValue("language").jsonPrimitive.contentOrNull
        Fixture(
            id = fixture.string("id"),
            gaps = fixture.getValue("gaps").jsonPrimitive.boolean,
            tone = fixture.getValue("tone").jsonPrimitive.boolean,
            replyingTo = fixture.getValue("replyingTo").jsonPrimitive.contentOrNull,
            request = CheckRequest(
                text = input.string("text"),
                language = languageCode?.let { requireNotNull(Language.forCode(it)) },
                punctuation = requireNotNull(Punctuation.tokens.decode(input.string("punctuation"))),
                judgments = requireNotNull(Judgments.tokens.decode(input.string("checks"))),
                settled = input.getValue("settled").jsonArray.map { value ->
                    val item = value.jsonObject
                    if (item["scope"]?.jsonPrimitive?.contentOrNull == "meaning") {
                        Settled.meaning(item.string("answer"))
                    } else Settled(item.string("about"), item.string("answer"))
                },
                native = input.string("native"),
                repair = input.getValue("repair").jsonPrimitive.boolean,
                writingProfiles = input.getValue("writingProfiles").jsonArray.map {
                    Json.decodeFromJsonElement<WritingProfile>(it)
                },
            ),
            expected = fixture.getValue("expected").jsonObject,
        )
    }

    data class Fixture(
        val id: String,
        val gaps: Boolean,
        val tone: Boolean,
        val replyingTo: String?,
        val request: CheckRequest,
        val expected: JsonObject,
    ) {
        fun prepare(): PreparedCheck = CheckComposition.prepare(
            base = PreparedCheckFixtures.base,
            gaps = PreparedCheckFixtures.gaps.takeIf { gaps },
            tone = PreparedCheckFixtures.tone.takeIf { tone },
            request = request,
            reply = PreparedCheckFixtures.reply,
            replyingTo = replyingTo,
        )

        fun assertGeneration(actual: GenerationRequest) {
            assertEquals(id, expected.string("systemSha256"), PreparedCheckFixtures.sha256(actual.system))
            assertEquals(id, expected.string("schemaSha256"), PreparedCheckFixtures.sha256(actual.schema.toString()))
            assertEquals(id, expected.string("user"), actual.user)
            assertEquals(id, expected.string("name"), actual.name)
            assertEquals(id, expected.getValue("maxTokens").jsonPrimitive.int, actual.maxTokens)
            assertEquals(id, expected.getValue("systemUtf8Bytes").jsonPrimitive.int, actual.system.toByteArray(Charsets.UTF_8).size)
            assertEquals(id, expected.getValue("schemaUtf8Bytes").jsonPrimitive.int, actual.schema.toString().toByteArray(Charsets.UTF_8).size)
            assertEquals(id, expected.getValue("userUtf8Bytes").jsonPrimitive.int, actual.user.toByteArray(Charsets.UTF_8).size)
        }
    }

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
