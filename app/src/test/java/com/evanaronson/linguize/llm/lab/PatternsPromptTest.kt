package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PatternsPromptTest {
    private val prompt = PatternsPrompt("system")
    private val brief = PatternBrief(
        "accents", "Written accents", 5, 3, "fewer",
        List(6) { ExampleBrief("esta$it", "está$it", if (it == 0) "Needs an accent." else null) },
    )

    @Test
    fun requestCarriesGroupsAndCappedExamplesAsData() {
        val request = prompt.request(listOf(brief), "Catalan")
        assertEquals("system", request.system)
        assertEquals("patterns", request.name)
        val user = Json.parseToJsonElement(request.user).jsonObject
        assertEquals("Catalan", user.getValue("explainIn").jsonPrimitive.content)
        val group = user.getValue("groups").jsonArray.single().jsonObject
        assertEquals("accents", group.getValue("id").jsonPrimitive.content)
        assertEquals("fewer", group.getValue("trend").jsonPrimitive.content)
        val examples = group.getValue("examples").jsonArray
        assertEquals(PatternsPrompt.MAX_EXAMPLES, examples.size)
        assertEquals("Needs an accent.", examples[0].jsonObject.getValue("reason").jsonPrimitive.content)
        assertTrue("reason" !in examples[1].jsonObject)
        assertEquals(listOf("summary", "tips"), request.schema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun longFieldsAreCut() {
        val long = brief.copy(examples = listOf(ExampleBrief("x".repeat(1_000), "y", null)))
        val user = Json.parseToJsonElement(prompt.request(listOf(long), "English").user).jsonObject
        val before = user.getValue("groups").jsonArray[0].jsonObject.getValue("examples").jsonArray[0].jsonObject.getValue("before")
        assertEquals(PatternsPrompt.MAX_FIELD, before.jsonPrimitive.content.length)
    }

    @Test
    fun readsAValidReplyAndTrimsIt() {
        val reply = prompt.read("""{"summary":"  Mostly accents. ","tips":[{"pattern":"accents","tip":" Check -ción. "}]}""", setOf("accents"))
        assertEquals("Mostly accents.", reply.summary)
        assertEquals(listOf(PatternTip("accents", "Check -ción.")), reply.tips)
    }

    @Test
    fun rejectsRepliesThatInventOrBreakTheContract() {
        val bad = listOf(
            """not json""",
            """{"summary":"","tips":[]}""",
            """{"summary":"ok","tips":[{"pattern":"gender","tip":"Invented group."}]}""",
            """{"summary":"ok","tips":[{"pattern":"accents","tip":"a"},{"pattern":"accents","tip":"b"}]}""",
            """{"summary":"ok","tips":[{"pattern":"accents","tip":"  "}]}""",
            """{"summary":"ok","tips":[],"extra":1}""",
        )
        bad.forEach { raw ->
            try {
                prompt.read(raw, setOf("accents"))
                fail("Accepted $raw")
            } catch (e: CheckFailure) {
                assertEquals(CheckFailure.Reason.BadResponse, e.reason)
            }
        }
    }

    @Test
    fun versionFollowsThePromptText() {
        assertEquals(PatternsPrompt("system").version, prompt.version)
        assertNotEquals(PatternsPrompt("other").version, prompt.version)
    }

    @Test
    fun shippedPromptAssetLoads() {
        val asset = File("src/main/assets/${PatternsPrompt.ASSET}").takeIf { it.exists() }
            ?: File("app/src/main/assets/${PatternsPrompt.ASSET}")
        val loaded = PatternsPrompt.load { asset.readText() }
        assertTrue(loaded.version.startsWith("lab-patterns-"))
        assertTrue(asset.readText().contains("untrusted data"))
    }
}
