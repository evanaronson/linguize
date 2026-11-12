package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.core.lab.Landing
import com.evanaronson.linguize.core.lab.LandingOverall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

/**
 * Lab: "How it lands" as part of every check (docs/lab/tone-check.md).
 *
 * The shipped prompt and schema are never changed. This composes onto them at request time:
 * [addendum] (`lab_tone_in_check.md`) after the instructions, and an optional `landing`
 * property ([landingSchema], `lab_tone_in_check_schema.json`) added to the answer's schema.
 * The rest of the answer is the shipped contract, read by the shipped parser; `landing` is
 * read separately and leniently by [landing], so an answer without it (older answers, History
 * replay) or with a bad one is still an ordinary check.
 */
class ToneInCheck(val addendum: String, landingSchema: String) {
    private val landingSchema: JsonObject = json.parseToJsonElement(landingSchema).jsonObject

    /** [baseSystem] (the shipped instructions, maybe with other rules), then the tone rules. */
    fun system(baseSystem: String): String = baseSystem.trimEnd() + "\n\n" + addendum.trim() + "\n"

    /**
     * [base] with `landing` added last. It is listed as required because strict structured output
     * (OpenAI) requires every property, but it may be null; reading never requires it.
     */
    fun schema(base: JsonObject): JsonObject {
        val properties = (base["properties"] as? JsonObject).orEmpty() + (FIELD to landingSchema)
        val composed = base.toMutableMap()
        composed["properties"] = JsonObject(properties)
        (base["required"] as? JsonArray)?.let { composed["required"] = JsonArray(it.filterNot { name -> name == JsonPrimitive(FIELD) } + JsonPrimitive(FIELD)) }
        (base["propertyOrdering"] as? JsonArray)?.let { composed["propertyOrdering"] = JsonArray(it.filterNot { name -> name == JsonPrimitive(FIELD) } + JsonPrimitive(FIELD)) }
        return JsonObject(composed)
    }

    /** The context's prompt hash: [baseHash] with a suffix for these rules and schema. */
    fun hash(baseHash: String): String = baseHash + "-t" + MessageDigest.getInstance("SHA-256")
        .digest("$addendum\u0000$landingSchema".toByteArray())
        .take(4)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val ASSET = "lab_tone_in_check.md"
        const val SCHEMA_ASSET = "lab_tone_in_check_schema.json"
        const val FIELD = "landing"

        private const val MAX_SUMMARY = 160
        private const val MAX_NOTE = 400
        private const val MAX_QUOTE = 300
        private val json = Json { ignoreUnknownKeys = true }

        /** Reads [ASSET] and [SCHEMA_ASSET] with [read] (the app's assets). */
        fun load(read: (String) -> String) = ToneInCheck(read(ASSET), read(SCHEMA_ASSET))

        /**
         * The answer's `landing`, judged on the text after its fixes; null when [raw] has none, or
         * none that can be trusted. Never throws: a bad reading never costs the check. A detail
         * that can't be trusted is dropped and the rest kept: a quote that isn't in the judged
         * text, an empty or overlong note. Scales are kept within 1 to 5.
         */
        fun landing(raw: String, original: String): Landing? = try {
            val answer = json.parseToJsonElement(raw).jsonObject
            val fields = answer[FIELD] as? JsonObject
            if (fields == null) {
                null
            } else {
                val corrected = (answer["corrected"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
                val hasErrors = (answer["has_errors"] as? JsonPrimitive)?.booleanOrNull == true
                val text = if (hasErrors && corrected.isNotEmpty()) corrected else original.trim()
                read(fields, text)
            }
        } catch (_: Exception) {
            null
        }

        private fun read(fields: JsonObject, text: String): Landing? {
            val overall = LandingOverall.tokens.decode(fields.string("overall") ?: return null) ?: return null
            val summary = fields.string("summary")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_SUMMARY } ?: return null
            val drivers = fields.strings("drivers")
                .mapNotNull { quote -> quote.takeIf { it.isNotBlank() && it in text } ?: quote.trim().takeIf { it.isNotEmpty() && it in text } }
                .filter { it.length <= MAX_QUOTE }
                .distinct()
                .take(Landing.MAX_DRIVERS)
            val notes = fields.strings("notes").map { it.trim() }.filter { it.isNotEmpty() && it.length <= MAX_NOTE }
                .distinct().take(Landing.MAX_NOTES)
            return Landing(
                text = text,
                overall = overall,
                summary = summary,
                warmth = fields.scale("warmth") ?: return null,
                directness = fields.scale("directness") ?: return null,
                formality = fields.scale("formality") ?: return null,
                drivers = drivers,
                notes = notes,
            )
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }

        private fun JsonObject.scale(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?.coerceIn(Landing.SCALE_MIN, Landing.SCALE_MAX)
    }
}
