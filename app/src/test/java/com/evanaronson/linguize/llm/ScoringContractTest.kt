package com.evanaronson.linguize.llm

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
            assertTrue(required.containsAll(listOf("confidence", "impact", "assumption_indices")))
            val links = item.getValue("properties").jsonObject.getValue("assumption_indices").jsonObject
            assertEquals("array", links.getValue("type").jsonPrimitive.content)
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
            val verdict = prompt.read(example).verdict
            val fields = Json.parseToJsonElement(example).jsonObject
            for (kind in listOf("fixes", "natural_changes")) {
                fields.getValue(kind).jsonArray.forEach { assertNotNull(it.jsonObject["assumption_indices"]) }
            }
            for (change in verdict.fixes + verdict.naturalChanges) {
                assertNotNull(change.assessment)
                assertTrue(change.assumptionIndices.all { it in verdict.assumptions.indices })
                changes++
            }
        }
        assertTrue(changes > 0)
    }

    private val prompt = Prompt.load { File("src/main/assets/$it").readText() }

    @Test fun badOptionalReferencesAreIgnoredWithoutLosingScoresOrCorrections() {
        val raw = answer("fixes", ""","confidence":1,"impact":2""")
            .replace("\"assumption_indices\":[]", """"assumption_indices":[0,0,-1,1,99,"0",false,null,{},1.5]""")
            .replace("\"assumptions\":[]", """"assumptions":[{"about":"Who","assumed":"I","words":"a","alternatives":["You"]}]""")
        val result = prompt.read(raw)
        assertEquals(raw, result.raw)
        assertEquals(listOf(0), result.verdict.fixes.single().assumptionIndices)
        assertEquals(1, result.verdict.fixes.single().assessment!!.confidence)
        for (invalid in listOf("null", "{}", "\"0\"", "true")) {
            val malformed = raw.replace("[0,0,-1,1,99,\"0\",false,null,{},1.5]", invalid)
            assertTrue(prompt.read(malformed).verdict.fixes.single().assumptionIndices.isEmpty())
        }
        assertTrue(prompt.read(answer("fixes", ""","confidence":1,"impact":2""")
            .replace(",\"assumption_indices\":[]", "")).verdict.fixes.single().assumptionIndices.isEmpty())
    }

    private fun envelope(status: String = "ok") =
        """{"status":"$status","language":"","meaning":"","assumptions":[],"has_errors":false,
            "corrected":"","fixes":[],"more_natural":false,"natural":"","natural_changes":[]}"""

    private fun answer(kind: String, scoreFields: String): String {
        val flag = if (kind == "fixes") "has_errors" else "more_natural"
        return envelope().replace("\"$flag\":false", "\"$flag\":true")
            .replace("\"$kind\":[]", """"$kind":[{"from":"a","to":"b","why":"Example","assumption_indices":[]$scoreFields}]""")
    }

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

    private fun assertInvalid(raw: String) {
        val failure = assertThrows(raw, CheckFailure::class.java) { prompt.read(raw) }
        assertEquals(CheckFailure.Reason.BadResponse, failure.reason)
        assertEquals(CheckFailure.Message.InvalidReply, failure.userMessage)
        assertEquals(raw, failure.raw)
    }

    @Test fun freshAnswersRequireEverySchemaDeclaredTopLevelField() {
        val complete = Json.parseToJsonElement(envelope()).jsonObject
        for (key in prompt.schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }) {
            val raw = JsonObject(complete - key).toString()
            assertInvalid(raw)
            // Defaults still decode older answers, apart from the always-required status.
            if (key != "status") assertEquals(prompt.parseVerdict(envelope()), prompt.parseVerdict(raw))
        }
        assertEquals(prompt.parseVerdict(envelope()), prompt.parseVerdict("""{"status":"ok"}"""))
        assertInvalid("""{"status":"ok"}""")
    }

    @Test fun freshAnswersRejectPayloadHiddenByFalseFlagsButReplayRetainsIt() {
        for ((kind, flag, target) in listOf(
            Triple("fixes", "has_errors", "corrected"),
            Triple("natural_changes", "more_natural", "natural"),
        )) {
            val scored = answer(kind, ""","confidence":3,"impact":1""")
                .replace("\"$flag\":true", "\"$flag\":false")
            val textOnly = envelope().replace("\"$target\":\"\"", "\"$target\":\"b\"")
            val both = scored.replace("\"$target\":\"\"", "\"$target\":\"b\"")
            for (raw in listOf(scored, textOnly, both)) {
                assertInvalid(raw)
                val replay = prompt.parseVerdict(raw)
                assertTrue(replay.corrected.isNotEmpty() || replay.natural.isNotEmpty() ||
                    replay.fixes.isNotEmpty() || replay.naturalChanges.isNotEmpty())
            }
        }
    }

    @Test fun freshNonOkAnswersRejectProposalPayload() {
        val proposals = listOf(
            answer("fixes", ""","confidence":3,"impact":1"""),
            answer("natural_changes", ""","confidence":3,"impact":1"""),
            envelope().replace("\"has_errors\":false", "\"has_errors\":true"),
            envelope().replace("\"more_natural\":false", "\"more_natural\":true"),
        )
        for (status in listOf("unclear", "wrong_language")) for (proposal in proposals) {
            val raw = proposal.replace("\"status\":\"ok\"", "\"status\":\"$status\"")
            assertInvalid(raw)
            assertEquals(status, prompt.parseVerdict(raw).status.token)
        }
    }

    @Test fun nonOkMeaningAndAssumptionsRemainReadableWithoutProposals() {
        for (status in listOf("unclear", "wrong_language")) {
            val raw = envelope(status).replace("\"meaning\":\"\"", "\"meaning\":\"A reading\"")
                .replace("\"assumptions\":[]", """"assumptions":[{"about":"Who","assumed":"I","words":"a","alternatives":["You"]}]""")
            assertEquals(prompt.parseVerdict(raw), prompt.read(raw).verdict)
        }
    }

    @Test fun freshTrueFlagsStillAllowIdentityDeletionAndWithheldProposals() {
        for ((kind, flag, target) in listOf(
            Triple("fixes", "has_errors", "corrected"),
            Triple("natural_changes", "more_natural", "natural"),
        )) {
            val scored = answer(kind, ""","confidence":3,"impact":1""")
            val identity = scored.replace("\"to\":\"b\"", "\"to\":\"a\"")
                .replace("\"$target\":\"\"", "\"$target\":\"a\"")
            val deletion = scored.replace("\"to\":\"b\"", "\"to\":\"\"")
            val empty = envelope().replace("\"$flag\":false", "\"$flag\":true")
            for (raw in listOf(scored, identity, deletion, empty)) {
                assertEquals(prompt.parseVerdict(raw), prompt.read(raw).verdict)
            }
        }
    }

    @Test fun scoreRequirementsDoNotInventSuggestionsForEmptyOrNonOkAnswers() {
        for (status in listOf("ok", "unclear", "wrong_language")) {
            val result = prompt.read(envelope(status))
            assertTrue(result.verdict.fixes.isEmpty())
            assertTrue(result.verdict.naturalChanges.isEmpty())
        }
    }

}
