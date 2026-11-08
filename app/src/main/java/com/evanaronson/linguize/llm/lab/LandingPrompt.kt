package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.core.lab.Landing
import com.evanaronson.linguize.core.lab.LandingDriver
import com.evanaronson.linguize.core.lab.LandingRisk
import com.evanaronson.linguize.core.lab.LandingVersion
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.GenerationRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * "How it lands": its own job, prompt and schema, separate from the check. The message is
 * read, never corrected; what comes back is advice the writer may ignore.
 */
class LandingPrompt(private val system: String, schema: String) {
    private val schema: JsonObject = json.parseToJsonElement(schema).jsonObject

    fun request(text: String, native: String) = GenerationRequest(
        system = system,
        user = buildJsonObject { put("explainIn", native); put("message", text) }.toString(),
        schema = schema,
        name = "lab_tone_check",
        maxTokens = MAX_OUTPUT_TOKENS,
    )

    /**
     * Reads the model's answer about [source]. Structural problems fail; individual
     * parts that can't be trusted (a quote that isn't in the message, a version that
     * changes nothing) are dropped, so one bad detail doesn't cost the whole reading.
     */
    fun read(raw: String, source: String): Landing = try {
        if (raw.length > MAX_RAW) throw invalid()
        val wire = json.decodeFromString<WireLanding>(raw)
        val risk = LandingRisk.tokens.decode(wire.risk) ?: throw invalid()
        val summary = wire.summary.trim()
        if (summary.isEmpty() || summary.length > MAX_PROSE) throw invalid()
        val drivers = wire.drivers.mapNotNull { driver ->
            val quote = driver.quote.takeIf { it.isNotBlank() && it in source } ?: driver.quote.trim().takeIf { it.isNotEmpty() && it in source }
            val effect = driver.effect.trim()
            if (quote == null || quote.length > MAX_QUOTE || effect.isEmpty() || effect.length > MAX_SHORT) null
            else LandingDriver(quote, effect)
        }.distinctBy { it.quote }.take(Landing.MAX_DRIVERS)
        val notes = wire.notes.map { it.trim() }.filter { it.isNotEmpty() && it.length <= MAX_PROSE }
            .distinct().take(Landing.MAX_NOTES)
        val maxVersion = maxOf(source.length * 3, source.length + 400)
        val versions = wire.versions.mapNotNull { version ->
            val text = version.text.trim()
            val label = version.label.trim()
            if (text.isEmpty() || text.length > maxVersion || label.isEmpty() || label.length > MAX_SHORT) null
            else LandingVersion(label, text, version.why.trim().take(MAX_PROSE))
        }.filter { normalized(it.text) != normalized(source) }
            .distinctBy { normalized(it.text) }
            // A message that already lands fine gets one optional version at most.
            .take(if (risk == LandingRisk.Fine) 1 else Landing.MAX_VERSIONS)
        Landing(
            variety = wire.variety.trim().take(MAX_SHORT),
            summary = summary,
            risk = risk,
            warmth = wire.warmth.coerceIn(Landing.SCALE_MIN, Landing.SCALE_MAX),
            directness = wire.directness.coerceIn(Landing.SCALE_MIN, Landing.SCALE_MAX),
            formality = wire.formality.coerceIn(Landing.SCALE_MIN, Landing.SCALE_MAX),
            drivers = drivers,
            notes = notes,
            versions = versions,
        )
    } catch (e: SerializationException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, raw = raw, userMessage = CheckFailure.Message.InvalidReply)
    } catch (e: IllegalArgumentException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, raw = raw, userMessage = CheckFailure.Message.InvalidReply)
    }

    private fun invalid() = CheckFailure(CheckFailure.Reason.BadResponse, userMessage = CheckFailure.Message.InvalidReply)

    private fun normalized(text: String) = text.trim().replace(WHITESPACE, " ")

    @Serializable
    private data class WireLanding(
        val variety: String,
        val summary: String,
        val risk: String,
        val warmth: Int,
        val directness: Int,
        val formality: Int,
        val drivers: List<WireDriver>,
        val notes: List<String>,
        val versions: List<WireVersion>,
    )

    @Serializable
    private data class WireDriver(val quote: String, val effect: String)

    @Serializable
    private data class WireVersion(val label: String, val text: String, val why: String)

    companion object {
        const val PROMPT_ASSET = "lab_tone_check_prompt.md"
        const val SCHEMA_ASSET = "lab_tone_check_schema.json"
        const val MAX_OUTPUT_TOKENS = 2048

        private const val MAX_RAW = 32_768
        private const val MAX_PROSE = 1_200
        private const val MAX_SHORT = 120
        private const val MAX_QUOTE = 600
        private val WHITESPACE = Regex("""\s+""")

        // Unknown keys are tolerated: the schema is the contract, and an extra field costs nothing.
        private val json = Json { ignoreUnknownKeys = true }

        /** Reads the prompt and schema with [read] (an asset reader); call off the main thread. */
        fun load(read: (String) -> String) = LandingPrompt(read(PROMPT_ASSET).trim(), read(SCHEMA_ASSET))
    }
}
