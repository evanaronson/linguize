package com.evanaronson.linguize.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** OpenAI's Responses API over plain REST. */
class OpenAIClient(private val http: OkHttpClient, private val prompt: Prompt) : ProviderClient {
    /** Strict structured output rejects Gemini's non-standard propertyOrdering. */
    private val schema = JsonObject(prompt.schema - "propertyOrdering")

    /** Reasoning effort "none"; models that reject it get their default instead. */
    private val reasoningNone = DroppableOption(refusal = "reasoning")

    override suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer {
        // One time limit for the whole check, the retry without reasoning control included.
        val deadline = Deadline(http)
        val (code, payload) = reasoningNone.send(model) { withoutReasoning ->
            val call = Request.Builder()
                .url("$BASE/responses")
                .header("Authorization", "Bearer $key")
                .post(body(model, request, withoutReasoning).toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            deadline.send(call)
        }
        if (code != 200) throw failure(code, payload)
        return prompt.read(answer(payload))
    }

    override suspend fun models(key: String): List<String> {
        val call = Request.Builder().url("$BASE/models").header("Authorization", "Bearer $key").build()
        val (code, payload) = Deadline(http).send(call)
        if (code != 200) throw failure(code, payload)
        val ids = readResponse(payload) { page ->
            page["data"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
        }
        return textModels(ids.filter { it.startsWith("gpt-") || Regex("^o\\d").containsMatchIn(it) })
    }

    private fun body(model: String, request: CheckRequest, noReasoning: Boolean): JsonObject = buildJsonObject {
        put("model", model)
        put("instructions", prompt.system)
        put("input", prompt.userMessage(request))
        put("store", false)
        put("max_output_tokens", Prompt.MAX_OUTPUT_TOKENS)
        // No reasoning keeps a check inside the ~2 s budget.
        if (noReasoning) putJsonObject("reasoning") { put("effort", "none") }
        putJsonObject("text") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("name", "check")
                put("strict", true)
                put("schema", schema)
            }
        }
    }

    internal companion object {
        const val BASE = "https://api.openai.com/v1"

        /** The text of the output message. Throws [CheckFailure]; TooLong when it was cut off. */
        fun answer(payload: String): String = readResponse(payload) { root ->
            if (root["status"]?.jsonPrimitive?.content == "incomplete") {
                val why = root["incomplete_details"]?.jsonObject?.get("reason")?.jsonPrimitive?.content
                // Half a JSON object would only fail as "not in the expected format", and again on retry.
                if (why == "max_output_tokens") throw truncated()
                if (why == "content_filter") throw declined("OpenAI")
                throw CheckFailure(CheckFailure.Reason.BadResponse, "The AI stopped before finishing. Reason given: ${why ?: "none"}")
            }
            root["output"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .filter { it["type"]?.jsonPrimitive?.content == "message" }
                .flatMap { it["content"]?.jsonArray.orEmpty() }
                .map { it.jsonObject }
                .filter { it["type"]?.jsonPrimitive?.content == "output_text" }
                .joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }
        }

        /** An HTTP error, with what OpenAI's own error codes say on top of the status code. */
        fun failure(code: Int, payload: String): CheckFailure {
            val error = errorObject(payload)
            val errorCode = error?.get("code")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            val param = error?.get("param")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            val message = errorMessage(payload).orEmpty()
            val reason = when {
                errorCode == "invalid_api_key" -> CheckFailure.Reason.BadKey
                errorCode == "model_not_found" -> CheckFailure.Reason.BadModel
                // An option the model doesn't take, or a project without access to the model.
                code == 400 && param == "model" -> CheckFailure.Reason.BadModel
                code == 403 && "model" in message.lowercase() -> CheckFailure.Reason.BadModel
                else -> null
            }
            return failureFor(code, payload, reason)
        }
    }
}
