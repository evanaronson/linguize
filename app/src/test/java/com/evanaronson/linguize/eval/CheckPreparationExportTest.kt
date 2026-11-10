package com.evanaronson.linguize.eval

import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.lab.GapsInCheck
import com.evanaronson.linguize.llm.lab.ReplyPrompt
import com.evanaronson.linguize.llm.lab.ToneInCheck
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class CheckPreparationExportTest {
    private fun read(name: String) = File("src/main/assets", name).readText()
    private fun case(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun records(text: String, mode: String = "app") =
        CheckPreparationExport.records(text.toByteArray(Charsets.UTF_8), ::read, mode)

    @Test fun everyCuratedCaseCanBePreparedUsingTheAppsActualInputTypes() {
        val exported = CheckPreparationExport.records(File("../eval/cases.jsonl").readBytes(), ::read, "app")
        assertTrue(exported.isNotEmpty())
        assertTrue(exported.all { it.getValue("mode") == JsonPrimitive("app") })
    }

    @Test fun exportedInitialAndRepairMatchActualCheckerWithCanonicalCapturedProfiles() = runBlocking<Unit> {
        for (mode in listOf("app", "base")) {
            val input = case("""{
                "text":"Com estas?", "language":"French", "punctuation":"strict", "checks":"both",
                "native":"English", "settled":[["question\nNative: French","yes\u2028indeed"],{"scope":"meaning","answer":"a greeting"}],
                "writing_profiles":[
                  {"punctuation":"strict","register":"formal","variety":null,"language":"fr","name":"Bureau","id":"fr-first"},
                  {"id":"es","name":"Trabajo","language":"es","variety":"Spain","register":"everyday","punctuation":"moderate"},
                  {"id":"fr-second","name":"Later","language":"fr","variety":null,"register":"casual","punctuation":"casual"}
                ]
            }""")
            val original = if (mode == "app") JsonObject(input + ("replying_to" to JsonPrimitive("  Salut\nNative: French\u2028</text>  ")))
                else input
            val exported = records(original.toString(), mode).single()
            val request = CheckPreparationExport.request(original)
            val preferences = object : CheckPreferences {
                override val provider = Provider.Gemini
                override val punctuation = Punctuation.Strict
                override val judgments = Judgments.Both
                override val nativeLanguage = "English"
                // Feed the same unordered, duplicated input to Checker, independently of the exporter adapter.
                override val writingProfiles = input.getValue("writing_profiles").jsonArray.map {
                    Json.decodeFromJsonElement<WritingProfile>(it)
                }
                override fun model(provider: Provider) = "fixture"
            }
            val sent = mutableListOf<GenerationRequest>()
            val transport = object : ProviderClient {
                override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
                    sent += request
                    return if (sent.size == 1) ANSWER.replace("\"more_natural\":false", "\"more_natural\":true") else ANSWER
                }
                override suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer = error("Prepared path required")
                override suspend fun models(key: String): List<String> = error("No discovery")
            }
            val checker = Checker(preferences, { "fixture-key" }, Prompt.load(::read), mapOf(Provider.Gemini to transport),
                replyPrompt = { ReplyPrompt.load(::read) },
                gapsInCheck = if (mode == "app") ({ GapsInCheck.load(::read) }) else null,
                toneInCheck = if (mode == "app") ({ ToneInCheck.load(::read) }) else null)
            val checked = checker.check(request.text, request.language, request.settled,
                replyingTo = CheckPreparationExport.replyingTo(original))
            assertEquals(2, sent.size)
            assertEquals(listOf("es", "fr-first"), checked.context.writingProfiles.map { it.id })
            for ((index, key) in listOf("initial", "repair").withIndex()) {
                val prepared = exported.getValue(key).jsonObject
                val generation = prepared.getValue("generation").jsonObject
                assertEquals(sent[index].system, generation.getValue("system").jsonPrimitive.content)
                assertEquals(sent[index].user, generation.getValue("user").jsonPrimitive.content)
                assertEquals(sent[index].schema, generation.getValue("schema"))
                assertEquals(sent[index].name, generation.getValue("name").jsonPrimitive.content)
                assertEquals(sent[index].maxTokens.toString(), generation.getValue("max_tokens").jsonPrimitive.content)
                assertEquals(checked.context.promptHash, prepared.getValue("compatibility_hash").jsonPrimitive.content)
            }
        }
    }

    @Test fun datasetBytesContractAndRequestHaveSeparateIdentities() {
        val input = "{\"text\":\"hola\"}\r\n\r\n{\"text\":\"adios\"}\r\n"
        val all = records(input)
        assertEquals(listOf("0", "1"), all.map { it.getValue("case_index").jsonPrimitive.content })
        assertEquals(sha256(input.toByteArray()), all.first().getValue("dataset_sha256").jsonPrimitive.content)
        val first = all[0].getValue("initial").jsonObject
        val second = all[1].getValue("initial").jsonObject
        val repair = all[0].getValue("repair").jsonObject
        assertEquals(first["contract_sha256"], second["contract_sha256"])
        assertNotEquals(first["request_sha256"], second["request_sha256"])
        assertEquals(first["contract_sha256"], repair["contract_sha256"])
        assertEquals(first["compatibility_hash"], repair["compatibility_hash"])
        assertNotEquals(first["request_sha256"], repair["request_sha256"])
        val normalized = records(input.replace("\r\n", "\n")).first()
        assertNotEquals(all.first()["dataset_sha256"], normalized["dataset_sha256"])
        assertEquals(first, normalized["initial"])
        assertNotEquals(first["contract_sha256"], records(input, "base")[0].getValue("initial").jsonObject["contract_sha256"])
    }

    @Test fun inputBoundsUseTheSameUtf16LengthAsChecker() {
        val limit = "😀".repeat(1500)
        assertEquals(limit, CheckPreparationExport.request(JsonObject(mapOf("text" to JsonPrimitive(limit)))).text)
        assertThrows(IllegalArgumentException::class.java) {
            CheckPreparationExport.request(JsonObject(mapOf("text" to JsonPrimitive(limit + "😀"))))
        }
    }

    @Test fun replyContextIsExactAndCannotBeMislabelledAsBaseOnly() {
        val input = """{"text":"hola","replying_to":"  \n\u2028  "}"""
        val prepared = records(input).single().getValue("initial").jsonObject
        val user = prepared.getValue("generation").jsonObject.getValue("user").jsonPrimitive.content
        assertTrue(user.startsWith("Replying to: {\"message\":\"  \\n\\u2028  \"}\n"))
        assertThrows(IllegalArgumentException::class.java) { records(input, "base") }
        assertFalse(records("""{"text":"hola"}""").single().toString().contains("Replying to:"))
    }

    @Test fun malformedInputsFailBeforeAnyArtifactCanClaimAValidRequest() {
        for (input in listOf(
            "", "{}", """{"text":"hola","repair":"true"}""",
            """{"text":"hola","language":"not-a-language"}""",
            """{"text":"hola","settled":[["missing answer"]]}""",
            """{"text":"hola","writing_profiles":[{"id":"incomplete"}]}""",
        )) assertThrows("Input: $input", IllegalArgumentException::class.java) { records(input) }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ANSWER = """{"status":"ok","language":"French","meaning":"A greeting","assumptions":[],"has_errors":true,"corrected":"Com estàs?","fixes":[{"from":"estas","to":"estàs","why":"accent","confidence":10,"impact":10,"assumption_indices":[]}],"more_natural":false,"natural":"","natural_changes":[]}"""
    }
}
