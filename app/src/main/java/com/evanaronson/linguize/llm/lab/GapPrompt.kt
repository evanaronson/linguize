package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.lab.GapFill
import com.evanaronson.linguize.core.lab.Gaps
import com.evanaronson.linguize.core.lab.QuotedFill
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.GenerationRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Fill the gaps has its own job, prompt and schema ([PROMPT_ASSET], [SCHEMA_ASSET]): its
 * messages never enter the correction engine, and its answers are placed on the source
 * by [GapFill.place], so the writer's own words come back unchanged.
 */
class GapPrompt(val system: String, schemaJson: String) {
    val schema: JsonObject = Json.parseToJsonElement(schemaJson).jsonObject

    /** [language] null lets the model detect it from the writer's own words. */
    fun request(text: String, language: Language?, native: String, profiles: List<WritingProfile>) = GenerationRequest(
        system = system,
        user = buildJsonObject {
            put("language", language?.name ?: "detect")
            put("explainIn", native)
            putJsonArray("profiles") {
                profiles.filter { language == null || it.language == language.code }.forEach { profile ->
                    addJsonObject {
                        put("language", Language.forCode(profile.language)?.name ?: profile.language)
                        put("register", profile.register.token)
                        profile.variety?.let { put("variety", it) }
                    }
                }
            }
            putJsonArray("markedGaps") { Gaps.marked(text).forEach { add(it.phrase) } }
            put("message", text)
        }.toString(),
        schema = schema,
        name = "fill_gaps",
        maxTokens = MAX_OUTPUT_TOKENS,
    )

    /** Reads and places the model's answer on [source]. Throws [CheckFailure] for anything unusable. */
    fun read(raw: String, source: String): GapFill = try {
        if (raw.length > MAX_RAW) throw IllegalArgumentException("Answer too long")
        val answer = json.decodeFromString<Answer>(raw)
        require(answer.fills.size <= MAX_FILLS)
        require(answer.language.length <= 64)
        answer.fills.forEach {
            require(it.original.length <= Gaps.MAX_PHRASE + 64 && it.filled.length <= 400)
            require(it.note.length <= 400 && it.alternatives.size <= GapFill.MAX_ALTERNATIVES)
            require(it.alternatives.all { alternative -> alternative.length <= 400 })
        }
        GapFill.place(source, answer.language, answer.fills.map {
            QuotedFill(it.original, it.filled, it.note, it.alternatives)
        })
    } catch (e: SerializationException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, userMessage = CheckFailure.Message.InvalidReply)
    } catch (e: IllegalArgumentException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, userMessage = CheckFailure.Message.InvalidReply)
    }

    @Serializable
    private data class Answer(val language: String, val fills: List<AnswerFill>)

    @Serializable
    private data class AnswerFill(
        val original: String,
        val filled: String,
        val note: String,
        val alternatives: List<String>,
    )

    companion object {
        const val PROMPT_ASSET = "lab_gap-filler_prompt.md"
        const val SCHEMA_ASSET = "lab_gap-filler_schema.json"
        const val MAX_OUTPUT_TOKENS = 2048
        private const val MAX_FILLS = 20
        private const val MAX_RAW = 32_768
        private val json = Json { ignoreUnknownKeys = false }

        /** Reads both assets through [read] (asset name to its text). */
        fun load(read: (String) -> String) = GapPrompt(read(PROMPT_ASSET).trim(), read(SCHEMA_ASSET))
    }
}
