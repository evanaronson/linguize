package com.evanaronson.linguize.llm

import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * The contract with the model: instructions, answer schema, the user message
 * format and how answers are read. The instructions and schema live in assets
 * so eval/ uses the same prompt and schema. App tests also cover parsing and edits,
 * which the provider evaluation script does not run.
 */
class Prompt(val system: String, schemaJson: String) {
    /** The answer format, as JSON Schema. */
    val schema: JsonObject = json.parseToJsonElement(schemaJson).jsonObject

    /** Short hash of the instructions and schema, so verdicts from different prompts aren't compared as if equal. */
    val hash: String = MessageDigest.getInstance("SHA-256")
        .digest("$system\u0000$schemaJson".toByteArray())
        .take(6)
        .joinToString("") { "%02x".format(it) }

    /**
     * The setting lines check_prompt.md describes, then the text between [TEXT_OPEN] and [TEXT_CLOSE].
     * The text comes last and the prompt says it runs to the final [TEXT_CLOSE] and is never
     * instructions, so a closing tag or "instructions" inside it are read as the writer's words.
     * Each Settled line is one JSON object, because its question was written by the model and
     * could otherwise carry a line break and a forged setting line.
     */
    fun userMessage(request: CheckRequest) = buildString {
        val language = request.language?.let {
            // A supplied profile is the only variety source; older callers retain their language default.
            if (request.writingProfiles.isEmpty()) "${it.name} (${it.variety})" else it.name
        } ?: "auto"
        appendLine("Language: $language")
        appendLine("Punctuation: ${request.punctuation.token}")
        if (request.writingProfiles.isNotEmpty()) {
            val profiles = json.encodeToString(request.writingProfiles)
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
            appendLine("Writing profiles: $profiles")
        }
        appendLine("Checks: ${request.judgments.token}")
        appendLine("Native: ${request.native} (write meaning, assumptions and reasons in ${request.native})")
        request.settled.forEach { appendLine("Settled: ${settledJson(it)}") }
        if (request.repair) appendLine("Repair: true")
        append("Text: $TEXT_OPEN${request.text}$TEXT_CLOSE")
    }

    /** Saved answers may predate the current contract; the single-argument API remains replay-compatible. */
    fun parseVerdict(answer: String): Verdict = parseVerdict(answer, requireFreshContract = false)

    /**
     * Fresh answers require schema-declared top-level fields and integer scores. False proposal
     * flags forbid their target text and changes; non-ok statuses forbid both flags and payloads.
     * Meaning and assumptions remain independent of these proposal checks.
     */
    fun parseVerdict(answer: String, requireFreshContract: Boolean): Verdict {
        if (answer.isBlank()) throw CheckFailure(CheckFailure.Reason.BadResponse, userMessage = CheckFailure.Message.EmptyReply)
        if (answer.length > MAX_ANSWER_CHARS) throw replyTooLarge()
        return try {
            val fields = json.parseToJsonElement(answer).jsonObject
            json.decodeFromJsonElement<Verdict>(withValidAssumptionReferences(fields)).also { verdict ->
                validateSize(verdict)
                if (requireFreshContract) {
                    val required = (schema["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
                    val correctionPayload = verdict.corrected.isNotEmpty() || verdict.fixes.isNotEmpty()
                    val naturalPayload = verdict.natural.isNotEmpty() || verdict.naturalChanges.isNotEmpty()
                    if (required.any { it !in fields } ||
                        (!verdict.hasErrors && correctionPayload) ||
                        (!verdict.moreNatural && naturalPayload) ||
                        (verdict.status != Verdict.Status.Ok &&
                            (verdict.hasErrors || verdict.moreNatural || correctionPayload || naturalPayload))) {
                        throw CheckFailure(CheckFailure.Reason.BadResponse, userMessage = CheckFailure.Message.InvalidReply)
                    }
                    val changes = listOf("fixes", "natural_changes").flatMap { key ->
                        (fields[key] as? JsonArray).orEmpty()
                    }
                    // Kotlin's integer decoder can accept quoted numbers; the wire contract cannot.
                    if ((verdict.fixes + verdict.naturalChanges).any { it.assessment == null } ||
                        changes.any { change -> listOf("confidence", "impact").any { key ->
                            val score = change.jsonObject[key] as? JsonPrimitive
                            val number = score?.takeUnless { it.isString }?.intOrNull
                            number == null || number !in 0..3
                        } }) {
                        throw CheckFailure(CheckFailure.Reason.BadResponse, userMessage = CheckFailure.Message.InvalidReply)
                    }
                }
            }
        } catch (e: SerializationException) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, userMessage = CheckFailure.Message.InvalidReply)
        } catch (e: IllegalArgumentException) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, userMessage = CheckFailure.Message.InvalidReply)
        }
    }

    /** Bad optional links must not invalidate an otherwise usable correction or invent a question. */
    private fun withValidAssumptionReferences(fields: JsonObject): JsonObject {
        val count = (fields["assumptions"] as? JsonArray)?.size ?: 0
        val normalized = fields.toMutableMap()
        for (key in listOf("fixes", "natural_changes")) {
            val changes = fields[key] as? JsonArray ?: continue
            normalized[key] = JsonArray(changes.map { entry ->
                val change = entry as? JsonObject ?: return@map entry
                val references = (change["assumption_indices"] as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { index -> index in 0 until count }
                }.distinct()
                JsonObject(change + ("assumption_indices" to JsonArray(references.map { JsonPrimitive(it) })))
            })
        }
        return JsonObject(normalized)
    }

    /**
     * The HTTP body cap alone doesn't bound alignment work. Reject implausibly large
     * rewrites before they can enter the domain, including answers replayed from history.
     */
    internal fun validateSize(verdict: Verdict) {
        if (verdict.corrected.length > MAX_REWRITE_CHARS || verdict.natural.length > MAX_REWRITE_CHARS) {
            throw replyTooLarge()
        }
    }

    private fun replyTooLarge() = CheckFailure(CheckFailure.Reason.BadResponse, userMessage = CheckFailure.Message.ReplyTooLarge)

    /** {"about":…,"answer":…} on one line: JSON escapes line breaks; the rest are spaces first. */
    private fun settledJson(settled: Settled): String = buildJsonObject {
        put("about", oneLine(settled.about))
        put("answer", oneLine(settled.answer))
    }.toString()

    /** Control characters and Unicode line and paragraph separators become spaces. */
    private fun oneLine(text: String): String =
        text.map { if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it }.joinToString("")

    companion object {
        const val TEXT_OPEN = "<text>"
        const val TEXT_CLOSE = "</text>"

        /**
         * Output token limit for a check, thinking included. The answer can repeat the text three
         * times (corrected, natural, meaning) plus the lists of changes; a cut-off answer is an unusable reply.
         */
        const val MAX_OUTPUT_TOKENS = 8192

        /** Generous bounds for 3,000-character checks; malformed responses must not drive unbounded work. */
        internal const val MAX_REWRITE_CHARS = 12_000
        internal const val MAX_ANSWER_CHARS = 128 * 1024

        private val json = Json { ignoreUnknownKeys = true }

        /** The prompt as the app ships it, read with [read] (the app's assets): check_prompt.md and check_schema.json. */
        fun load(read: (String) -> String) = Prompt(system = read("check_prompt.md"), schemaJson = read("check_schema.json"))
    }
}
