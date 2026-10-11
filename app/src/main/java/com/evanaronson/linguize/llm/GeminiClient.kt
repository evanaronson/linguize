package com.evanaronson.linguize.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The Gemini Developer API over plain REST. The official SDKs pull in Firebase
 * or Ktor plus Google auth; two endpoints don't need them.
 */
class GeminiClient(private val http: OkHttpClient, private val prompt: Prompt) : ProviderClient {
    /** Minimal thinking; models that reject it get their default instead. */
    private val minimal = DroppableOption(refusal = "thinking")

    override suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer {
        // One time limit for the whole check, the retry without minimal thinking included.
        val deadline = Deadline(http)
        val (code, payload) = minimal.send(model) { withMinimal ->
            val call = Request.Builder()
                .url("$BASE/models/$model:generateContent")
                .header("x-goog-api-key", key)
                .post(body(request, withMinimal).toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            deadline.send(call)
        }
        if (code != 200) throw failure(code, payload)
        return prompt.read(answer(payload))
    }

    override suspend fun models(key: String): List<String> {
        val ids = mutableListOf<String>()
        val deadline = Deadline(http)
        var pageToken: String? = null
        val seenTokens = mutableSetOf<String>()
        // At most MAX_MODEL_PAGES pages, and a token seen before ends the list: a server that
        // keeps sending a next page can't keep this loop going until the deadline.
        for (pageNumber in 1..MAX_MODEL_PAGES) {
            val url = "$BASE/models".toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", "1000")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val (code, payload) = deadline.send(Request.Builder().url(url).header("x-goog-api-key", key).build())
            if (code != 200) throw failure(code, payload)
            val next = readResponse(payload) { page ->
                for (model in page["models"]?.jsonArray.orEmpty().map { it.jsonObject }) {
                    val id = model["name"]?.jsonPrimitive?.content?.removePrefix("models/") ?: continue
                    val methods = model["supportedGenerationMethods"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                    if (id.startsWith("gemini") && "generateContent" in methods) ids += id
                }
                page["nextPageToken"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
            }
            if (next == null || !seenTokens.add(next)) break
            pageToken = next
        }
        return textModels(ids)
    }

    private fun body(request: CheckRequest, minimalThinking: Boolean): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { add(buildJsonObject { put("text", prompt.system) }) }
        }
        putJsonArray("contents") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", prompt.userMessage(request)) }) }
                },
            )
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseJsonSchema", prompt.schema)
            put("maxOutputTokens", Prompt.MAX_OUTPUT_TOKENS)
            // Minimal thinking keeps a check inside the ~2 s budget.
            if (minimalThinking) putJsonObject("thinkingConfig") { put("thinkingLevel", "MINIMAL") }
        }
    }

    internal companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"

        /** Pages of models read at most; one page of 1000 holds them all today. */
        const val MAX_MODEL_PAGES = 10

        /** The answer text, without any thought parts. Throws [CheckFailure]; TooLong when it was cut off. */
        fun answer(payload: String): String = readResponse(payload) { root ->
            val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw if (root["promptFeedback"]?.jsonObject?.get("blockReason") != null) {
                    declined("Gemini")
                } else {
                    CheckFailure(CheckFailure.Reason.BadResponse, "The AI sent an empty reply.")
                }
            val finish = candidate["finishReason"]?.jsonPrimitive?.content
            if (finish in BLOCKED_FINISHES) throw declined("Gemini")
            // Half a JSON object would only fail as "not in the expected format", and again on retry.
            if (finish == "MAX_TOKENS") throw truncated()
            val text = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .filterNot { it["thought"]?.jsonPrimitive?.booleanOrNull == true }
                .joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }
            if (text.isBlank()) {
                throw CheckFailure(CheckFailure.Reason.BadResponse, "The AI sent no reply. Reason given: ${finish ?: "none"}")
            }
            text
        }

        /** An HTTP error, with what Gemini's own errors say on top of the status code. */
        fun failure(code: Int, payload: String): CheckFailure {
            val reason = when {
                code == 400 && "api_key_invalid" in payload.lowercase() -> CheckFailure.Reason.BadKey
                // Gemini answers 403 for models a key isn't allowed to use, and 400 for options a model doesn't support.
                // Only the message says which: a blocked key's names the method ("ModelService.ListModels"), not a model.
                (code == 403 || code == 400) && MODEL_WORD.containsMatchIn(errorMessage(payload).orEmpty()) -> CheckFailure.Reason.BadModel
                else -> null
            }
            return failureFor(code, payload, reason)
        }

        /** Finish reasons that mean Gemini refused the text, not that something broke. */
        private val BLOCKED_FINISHES = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY")

        /** "model" or "models" as a word of its own, not inside a service's name. */
        private val MODEL_WORD = Regex("""\bmodels?\b""", RegexOption.IGNORE_CASE)
    }
}
