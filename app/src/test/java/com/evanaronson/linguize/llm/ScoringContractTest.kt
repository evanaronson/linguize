package com.evanaronson.linguize.llm

import com.evanaronson.linguize.core.Verdict
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ScoringContractTest {
    @Test fun productionSchemaRequiresBoundedIntegersForBothKinds() {
        val schema = Json.parseToJsonElement(File("src/main/assets/check_schema.json").readText()).jsonObject
        val properties = schema.getValue("properties").jsonObject
        for (kind in listOf("fixes", "natural_changes")) {
            val item = properties.getValue(kind).jsonObject.getValue("items").jsonObject
            val required = item.getValue("required").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(required.containsAll(listOf("confidence", "impact")))
            for (score in listOf("confidence", "impact")) {
                val field = item.getValue("properties").jsonObject.getValue(score).jsonObject
                assertEquals("integer", field.getValue("type").jsonPrimitive.content)
                assertEquals(0, field.getValue("minimum").jsonPrimitive.int)
                assertEquals(3, field.getValue("maximum").jsonPrimitive.int)
            }
        }
    }

    @Test fun everyProductionExampleChangeHasBothScores() {
        val examples = File("src/main/assets/check_prompt.md").readLines().filter { it.startsWith("{\"status\"") }
        assertTrue(examples.isNotEmpty())
        var changes = 0
        for (example in examples) {
            val verdict = Json.decodeFromString<Verdict>(example)
            for (change in verdict.fixes + verdict.naturalChanges) {
                assertNotNull(change.assessment)
                changes++
            }
        }
        assertTrue(changes > 0)
    }

    private val prompt = Prompt(system = "", schemaJson = "{}")

    private fun answer(kind: String, scoreFields: String) =
        """{"status":"ok","$kind":[{"from":"a","to":"b","why":"Example"$scoreFields}]}"""

    @Test fun freshAnswersRequireBothScoresButLegacyReplayCanBeUnassessed() {
        for (kind in listOf("fixes", "natural_changes")) {
            val raw = answer(kind, "")
            val replay = prompt.parseVerdict(raw)
            assertNull((replay.fixes + replay.naturalChanges).single().assessment)
            val failure = assertThrows(CheckFailure::class.java) { prompt.read(raw) }
            assertEquals(CheckFailure.Message.InvalidReply, failure.userMessage)
            assertEquals(raw, failure.raw)
        }
    }

    @Test fun freshAnswersAcceptZeroAndKeepTheExactRawAssessment() {
        for (kind in listOf("fixes", "natural_changes")) {
            val raw = answer(kind, ""","confidence":0,"impact":0""")
            val result = prompt.read(raw)
            assertEquals(raw, result.raw)
            val change = (result.verdict.fixes + result.verdict.naturalChanges).single()
            assertEquals(0, change.assessment!!.confidence)
            assertEquals(0, change.assessment!!.impact)
        }
    }

    @Test fun freshAnswersRejectMissingNullUnpairedAndNonIntegerScores() {
        val invalid = listOf(
            ""","confidence":null,"impact":null""",
            ""","confidence":1""",
            ""","impact":1""",
            ""","confidence":-1,"impact":1""",
            ""","confidence":1,"impact":4""",
            ""","confidence":1.5,"impact":1""",
            ""","confidence":true,"impact":1""",
            ""","confidence":"3","impact":1""",
            ""","confidence":1,"impact":"0"""",
        )
        for (kind in listOf("fixes", "natural_changes")) for (fields in invalid) {
            val raw = answer(kind, fields)
            val failure = assertThrows(raw, CheckFailure::class.java) { prompt.read(raw) }
            assertEquals(CheckFailure.Message.InvalidReply, failure.userMessage)
            assertEquals(raw, failure.raw)
        }
    }

    @Test fun scoreRequirementsDoNotInventSuggestionsForEmptyOrNonOkAnswers() {
        for (status in listOf("ok", "unclear", "wrong_language")) {
            val result = prompt.read("""{"status":"$status","fixes":[],"natural_changes":[]}""")
            assertTrue(result.verdict.fixes.isEmpty())
            assertTrue(result.verdict.naturalChanges.isEmpty())
        }
    }

}
