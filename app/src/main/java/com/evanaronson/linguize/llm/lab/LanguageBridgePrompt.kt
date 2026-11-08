package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.languagebridge.BridgeTargets
import com.evanaronson.linguize.core.languagebridge.Bridged
import com.evanaronson.linguize.core.languagebridge.Correspondence
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.GenerationRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The language bridge's own contract with the model: [system] and [schema] come from
 * `lab_language-bridge_prompt.md` and `lab_language-bridge_schema.json`. The shipped check
 * prompt is never sent, and the answer never enters the correction engine.
 */
class LanguageBridgePrompt(val system: String, val schema: JsonObject) {

    /** Asks for [text] in [target]'s language, register, variety and punctuation. */
    fun request(text: String, target: WritingProfile, sourceHint: String?, native: String) = GenerationRequest(
        system = system,
        user = buildJsonObject {
            putJsonObject("target") {
                put("language", BridgeTargets.englishName(target.language))
                put("code", target.language)
                target.variety?.let { put("variety", it) }
                put("register", target.register.token)
                put("punctuation", target.punctuation.token)
            }
            if (sourceHint != null) put("sourceHint", BridgeTargets.englishName(sourceHint)) else put("sourceHint", JsonNull)
            put("explainIn", native)
            put("message", text)
        }.toString(),
        schema = schema,
        name = "language_bridge",
        maxTokens = MAX_OUTPUT_TOKENS,
    )

    /**
     * Reads the answer for [source]. The text and status must be usable or this throws
     * [CheckFailure]; a correspondence that doesn't quote both texts exactly is dropped
     * rather than failing the whole answer, since the text is still worth having.
     */
    fun read(raw: String, source: String): Bridged = try {
        if (raw.length > MAX_RAW) invalid()
        val root = json.parseToJsonElement(raw).jsonObject
        val status = when (root.string("status")) {
            "ok" -> Bridged.Status.Ok
            "same_language" -> Bridged.Status.SameLanguage
            "unclear" -> Bridged.Status.Unclear
            else -> invalid()
        }
        val sourceLanguage = root.string("source_language")?.trim()?.take(64) ?: invalid()
        if (status != Bridged.Status.Ok) {
            Bridged(status, sourceLanguage, "", emptyList())
        } else {
            val text = root.string("text")?.trim().orEmpty()
            if (text.isEmpty() || text.length > MAX_TEXT) invalid()
            val pairs = (root["pairs"] as? JsonArray ?: invalid()).take(MAX_PAIRS)
                .mapNotNull { (it as? JsonObject)?.let { pair -> correspondence(pair, source, text) } }
            Bridged(status, sourceLanguage, text, inSourceOrder(pairs, source))
        }
    } catch (e: SerializationException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, raw = raw, userMessage = CheckFailure.Message.InvalidReply)
    } catch (e: IllegalArgumentException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, raw = raw, userMessage = CheckFailure.Message.InvalidReply)
    }

    private fun correspondence(pair: JsonObject, source: String, text: String): Correspondence? {
        val from = pair.string("source")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_EXCERPT } ?: return null
        val to = pair.string("target")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_EXCERPT } ?: return null
        val kind = pair.string("kind")?.let(Correspondence.Kind::read) ?: return null
        val note = pair.string("note")?.trim()?.takeIf { it.length <= MAX_NOTE } ?: return null
        if (from !in source || to !in text) return null
        return Correspondence(from, to, kind, note)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun invalid(): Nothing =
        throw CheckFailure(CheckFailure.Reason.BadResponse, userMessage = CheckFailure.Message.InvalidReply)

    private val json = Json

    companion object {
        const val PROMPT_ASSET = "lab_language-bridge_prompt.md"
        const val SCHEMA_ASSET = "lab_language-bridge_schema.json"
        const val MAX_OUTPUT_TOKENS = 4096
        const val MAX_PAIRS = 24
        private const val MAX_RAW = 48_000
        private const val MAX_TEXT = 12_000
        private const val MAX_EXCERPT = 400
        private const val MAX_NOTE = 600

        /** Reads both assets through [read] (asset name to its text). */
        fun load(read: (String) -> String) = LanguageBridgePrompt(
            read(PROMPT_ASSET).trim(),
            Json.parseToJsonElement(read(SCHEMA_ASSET)).jsonObject,
        )

        /**
         * Orders [pairs] by where their excerpts occur in [source], each search starting after
         * the previous match so a repeated word maps to its next occurrence; duplicates go.
         */
        internal fun inSourceOrder(pairs: List<Correspondence>, source: String): List<Correspondence> {
            var cursor = 0
            return pairs.distinctBy { it.source to it.target }
                .map { pair ->
                    val after = source.indexOf(pair.source, cursor)
                    val at = if (after >= 0) after else source.indexOf(pair.source)
                    if (after >= 0) cursor = after + pair.source.length
                    at to pair
                }
                .sortedBy { it.first }
                .map { it.second }
        }
    }
}
