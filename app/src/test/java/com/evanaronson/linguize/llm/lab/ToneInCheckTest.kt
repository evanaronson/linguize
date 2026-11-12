package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.core.lab.LandingOverall
import com.evanaronson.linguize.llm.Prompt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Lab: How it lands is composed onto the shipped check at request time and read leniently from its answer. */
class ToneInCheckTest {
    private val base = Prompt.load { File("src/main/assets/$it").readText() }
    private val tone = ToneInCheck.load { File("src/main/assets/$it").readText() }

    @Test
    fun theShippedAssetsAreByteForByteUnchanged() {
        // The shipped prompt and schema are what eval/ runs; Lab features compose onto them instead.
        assertEquals("affe4ca3901fea5218d215a17a990e76106d3b390471fc506d29605e3320924b", sha256("check_prompt.md"))
        assertEquals("e6a80b86811a88caf677f5592c06d56f3d71abf6aa1995315b580e3dd0fe9e89", sha256("check_schema.json"))
        tone.schema(base.schema)
        tone.system(base.system)
        assertEquals(File("src/main/assets/check_prompt.md").readText(), base.system)
        assertTrue("landing" !in (base.schema["properties"] as JsonObject))
    }

    @Test
    fun theSchemaIsTheShippedOneWithAnOptionalLandingAddedLast() {
        val composed = tone.schema(base.schema)
        val shipped = base.schema["properties"] as JsonObject
        val properties = composed["properties"] as JsonObject
        assertEquals(shipped.keys + "landing", properties.keys)
        shipped.forEach { (name, schema) -> assertEquals(schema, properties[name]) }
        assertEquals(base.schema["required"]!!.jsonArray + JsonPrimitive("landing"), composed["required"])
        assertEquals(base.schema["propertyOrdering"]!!.jsonArray.last().jsonPrimitive.content, "natural_changes")
        assertEquals("landing", composed["propertyOrdering"]!!.jsonArray.last().jsonPrimitive.content)
        assertEquals(base.schema["additionalProperties"], composed["additionalProperties"])
        // Strict structured output needs it listed; it may be null.
        val choices = properties.getValue("landing").jsonObject.getValue("anyOf").jsonArray
        assertEquals("null", choices[1].jsonObject.getValue("type").jsonPrimitive.content)
        val landing = choices[0].jsonObject
        assertEquals((landing["properties"] as JsonObject).keys, (landing["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet())
        // Composing twice doesn't list it twice.
        assertEquals(composed, tone.schema(composed))
    }

    @Test
    fun theRulesFollowTheShippedInstructionsAndTheHashMarksThem() {
        val system = tone.system(base.system)
        assertTrue(system.startsWith(base.system.trimEnd()))
        assertTrue(system.trimEnd().endsWith(tone.addendum.trim()))
        listOf("`landing`", "`corrected`", "`Native:`", "`natural_changes`", "lands_well", "Casual is not wrong", "`why`").forEach {
            assertTrue("missing $it", it in tone.addendum)
        }
        val hash = tone.hash(base.hash)
        assertTrue(hash.startsWith(base.hash + "-t"))
        assertEquals(hash, tone.hash(base.hash))
        assertNotEquals(hash, ToneInCheck("other rules", "{}").hash(base.hash))
    }

    @Test
    fun aLandingIsReadFromTheTextAfterTheFixes() {
        val landing = ToneInCheck.landing(answer(LANDING), ORIGINAL)!!
        assertEquals(CORRECTED, landing.text)
        assertEquals(LandingOverall.CouldLandDifferently, landing.overall)
        assertEquals("a bit demanding", landing.summary)
        assertEquals(listOf(2, 5, 3), listOf(landing.warmth, landing.directness, landing.formality))
        // "envíes" is only in the corrected text; "envies" (the original's spelling) isn't, so it's dropped.
        assertEquals(listOf("Quiero que", "envíes"), landing.drivers)
        assertEquals(listOf("Requests are usually softened."), landing.notes)
    }

    @Test
    fun withNothingToFixItIsReadFromTheTextItself() {
        val json = answer(LANDING, hasErrors = false)
        assertEquals(ORIGINAL, ToneInCheck.landing(json, ORIGINAL)!!.text)
        assertEquals(listOf("Quiero que"), ToneInCheck.landing(json, ORIGINAL)!!.drivers.take(1))
    }

    @Test
    fun anAnswerWithoutOrWithABadLandingHasNone() {
        assertNull(ToneInCheck.landing(answer(null), ORIGINAL))
        assertNull(ToneInCheck.landing(answer("null"), ORIGINAL))
        assertNull(ToneInCheck.landing(answer(LANDING.replace("could_land_differently", "meh")), ORIGINAL))
        assertNull(ToneInCheck.landing(answer(LANDING.replace("\"a bit demanding\"", "\"  \"")), ORIGINAL))
        assertNull(ToneInCheck.landing(answer(LANDING.replace("\"warmth\":2", "\"warmth\":\"2\"")), ORIGINAL))
        assertNull(ToneInCheck.landing("not json", ORIGINAL))
        // The rest of the answer is still the shipped contract.
        assertEquals("ok", base.parseVerdict(answer(null), requireFreshContract = true).status.token)
        assertEquals("ok", base.parseVerdict(answer(LANDING), requireFreshContract = true).status.token)
    }

    @Test
    fun scalesAreKeptWithinRangeAndNotesBounded() {
        val wild = LANDING.replace("\"warmth\":2", "\"warmth\":9").replace("\"directness\":5", "\"directness\":0")
            .replace("\"notes\":[\"Requests are usually softened.\"]", "\"notes\":[\"a\",\"b\",\"\",\"c\",\"d\"]")
        val landing = ToneInCheck.landing(answer(wild), ORIGINAL)!!
        assertEquals(5, landing.warmth)
        assertEquals(1, landing.directness)
        assertEquals(listOf("a", "b", "c"), landing.notes)
    }

    private fun sha256(asset: String) = MessageDigest.getInstance("SHA-256")
        .digest(File("src/main/assets/$asset").readBytes()).joinToString("") { "%02x".format(it) }

    private fun answer(landing: String?, hasErrors: Boolean = true): String {
        val fixes = if (hasErrors) "[{\"from\":\"envies\",\"to\":\"envíes\",\"why\":\"Accent\",\"confidence\":10,\"impact\":3,\"assumption_indices\":[]}]" else "[]"
        val tail = landing?.let { ",\"landing\":$it" }.orEmpty()
        return "{\"status\":\"ok\",\"language\":\"Spanish\",\"meaning\":\"I want you to send me the contract.\",\"assumptions\":[]," +
            "\"has_errors\":$hasErrors,\"corrected\":\"${if (hasErrors) CORRECTED else ""}\",\"fixes\":$fixes," +
            "\"more_natural\":false,\"natural\":\"\",\"natural_changes\":[]$tail}"
    }

    private companion object {
        const val ORIGINAL = "Quiero que me envies el contrato."
        const val CORRECTED = "Quiero que me envíes el contrato."
        const val LANDING = "{\"overall\":\"could_land_differently\",\"summary\":\"a bit demanding\",\"warmth\":2,\"directness\":5," +
            "\"formality\":3,\"drivers\":[\"Quiero que\",\"envíes\",\"envies\",\"Quiero que\"],\"notes\":[\"Requests are usually softened.\"]}"
    }
}
