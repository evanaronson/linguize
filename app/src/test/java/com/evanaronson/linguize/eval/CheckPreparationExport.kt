package com.evanaronson.linguize.eval

import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.language.LanguageCodes
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.PreparedCheck
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.lab.CheckComposition
import com.evanaronson.linguize.llm.lab.GapsInCheck
import com.evanaronson.linguize.llm.lab.ReplyPrompt
import com.evanaronson.linguize.llm.lab.ToneInCheck
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/** Offline tooling entry point. Evaluation consumes the app's prepared requests, not a Python mirror. */
object CheckPreparationExport {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) { "Expected assets directory, cases JSONL, output JSONL, and app|base" }
        val assets = File(args[0])
        val input = File(args[1])
        val output = File(args[2])
        require(input.canonicalFile != output.canonicalFile) { "Output must not replace the input cases" }
        val records = records(input.readBytes(), { File(assets, it).readText(Charsets.UTF_8) }, args[3])
        // Validate the entire dataset before creating output. No provider calls or credentials are used.
        output.parentFile?.mkdirs()
        output.writeText(records.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
        println("Prepared ${records.size} ${args[3]} check requests: ${output.absolutePath}")
    }

    internal fun records(input: ByteArray, read: (String) -> String, mode: String): List<JsonObject> {
        require(mode in setOf("app", "base")) { "Unknown preparation mode: $mode" }
        val base = Prompt.load(read)
        val gaps = if (mode == "app") GapsInCheck.load(read) else null
        val tone = if (mode == "app") ToneInCheck.load(read) else null
        val reply by lazy { ReplyPrompt.load(read) }
        val dataset = sha256(input)
        val cases = input.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }.toList()
        require(cases.isNotEmpty()) { "The evaluation dataset has no cases" }
        return cases.mapIndexed { index, case ->
            val request = try {
                request(case)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid request in evaluation case ${index + 1}: ${error.message}", error)
            }
            val replyingTo = replyingTo(case)
            require(mode == "app" || replyingTo == null) { "Reply context requires app preparation mode" }
            fun prepare(request: CheckRequest) = CheckComposition.prepare(
                base, gaps, tone, request, if (replyingTo != null) reply else null, replyingTo,
            )
            buildJsonObject {
                put("version", 1)
                put("mode", mode)
                put("case_index", index)
                put("case", case)
                put("dataset_sha256", dataset)
                put("initial", preparedRequestRecord(mode, prepare(request)))
                put("repair", preparedRequestRecord(mode, prepare(request.copy(repair = true))))
            }
        }
    }

    /** Checker accepts the caller's exact message; UI normalization belongs to its own boundary. */
    internal fun replyingTo(case: JsonObject): String? = case.optionalString("replying_to")

    /** Adapts eval inputs to the same request-time choices captured by Checker.context. */
    internal fun request(case: JsonObject): CheckRequest {
        val text = case.requiredString("text")
        require(text.length <= Checker.MAX_CHARS) { "Input exceeds ${Checker.MAX_CHARS} UTF-16 characters" }
        val pinned = case.optionalString("language")?.takeUnless { it == "auto" }
        val language = pinned?.let {
            requireNotNull(Language.forCode(LanguageCodes.fromEnglishName(it))) { "Unknown language: $it" }
        }
        val punctuation = requireNotNull(Punctuation.tokens.decode(case.optionalString("punctuation") ?: "moderate"))
        val judgments = requireNotNull(Judgments.tokens.decode(case.optionalString("checks") ?: "both"))
        val profiles = case.array("writing_profiles").map { Json.decodeFromJsonElement<WritingProfile>(it) }
            .distinctBy { it.language }.sortedBy { it.language }.toList()
        val settled = case.array("settled").map { item ->
            if (item is JsonArray) {
                require(item.size == 2) { "Settled pairs need an about and an answer" }
                Settled(item[0].string(), item[1].string())
            } else {
                val fields = item.jsonObject
                when (fields.optionalString("scope") ?: "assumption") {
                    "meaning" -> Settled.meaning(fields.requiredString("answer"))
                    "assumption" -> Settled(fields.requiredString("about"), fields.requiredString("answer"))
                    else -> error("Unknown clarification scope")
                }
            }
        }
        val repair = case["repair"]?.let {
            requireNotNull((it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull) {
                "repair must be a Boolean"
            }
        } ?: false
        return CheckRequest(text, language, punctuation, judgments, settled,
            case.optionalString("native") ?: "English", repair, profiles)
    }

    /** Versioned fingerprints identify actual bytes separately from History's compatible hash. */
    internal fun preparedRequestRecord(mode: String, prepared: PreparedCheck): JsonObject {
        val request = prepared.generation
        val parser = buildJsonObject {
            put("policy", prepared.parserPolicy)
            put("prompt_hash", prepared.baseReaderHash)
        }
        val generation = buildJsonObject {
            put("system", request.system)
            put("user", request.user)
            put("schema", request.schema)
            put("name", request.name)
            put("max_tokens", request.maxTokens)
        }
        val contract = buildJsonObject {
            put("mode", mode)
            put("system", request.system)
            put("schema", request.schema)
            put("name", request.name)
            put("max_tokens", request.maxTokens)
            put("parser", parser)
        }.digest()
        val fingerprint = buildJsonObject {
            put("contract_sha256", contract)
            put("user", request.user)
            put("compatibility_hash", prepared.compatibilityHash)
        }.digest()
        return buildJsonObject {
            put("generation", generation)
            put("compatibility_hash", prepared.compatibilityHash)
            put("parser", parser)
            put("contract_sha256", contract)
            put("request_sha256", fingerprint)
        }
    }

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeUnless { it is JsonNull }?.string()
    private fun JsonObject.requiredString(name: String) = requireNotNull(optionalString(name)) { "$name is required" }
    private fun JsonElement.string(): String = requireNotNull((this as? JsonPrimitive)?.takeIf { it.isString }) {
        "Expected a string"
    }.content
    private fun JsonObject.array(name: String): JsonArray =
        get(name)?.let { requireNotNull(it as? JsonArray) { "$name must be an array" } } ?: JsonArray(emptyList())
    private fun JsonObject.digest() = sha256(toString().toByteArray(Charsets.UTF_8))
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
