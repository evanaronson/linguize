package com.evanaronson.linguize.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** OpenAI's Responses API over plain REST. */
class OpenAIClient(http: OkHttpClient, private val prompt: Prompt) : ProviderClient {
    private val http = http.forProviderRequests()

    /** Minimal supported reasoning; models that reject the option get their default instead. */
    private val reasoningNone = DroppableOption(refusal = "reasoning")

    override suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer =
        prompt.read(generate(key, model, prompt.generation(request)))

    override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
        // One time limit for the whole check, the retry without reasoning control included.
        val deadline = Deadline(http)
        val (code, payload) = reasoningNone.send(model) { withNoReasoning ->
            val call = Request.Builder()
                .url("$BASE/responses")
                .header("Authorization", "Bearer $key")
                .post(body(model, request, withNoReasoning).toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            deadline.send(call)
        }
        if (code != 200) throw failure(code, payload)
        return answer(payload)
    }

    override suspend fun models(key: String): List<String> {
        val call = Request.Builder().url("$BASE/models").header("Authorization", "Bearer $key").build()
        val (code, payload) = Deadline(http).send(call)
        if (code != 200) throw failure(code, payload)
        val ids = readResponse(payload) { page ->
            requireNotNull(page["data"]).jsonArray.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
        }
        return textModels(ids.filter { it == "chat-latest" || it.startsWith("gpt-") || Regex("^o\\d").containsMatchIn(it) })
    }

    private fun body(model: String, request: GenerationRequest, noReasoning: Boolean): JsonObject = buildJsonObject {
        put("model", model)
        put("instructions", request.system)
        put("input", request.user)
        put("store", false)
        put("max_output_tokens", request.maxTokens)
        // Use the least reasoning supported by the current family. Unknown models
        // retain the existing retry without this option if they reject it.
        if (noReasoning) putJsonObject("reasoning") {
            val needsLow = listOf("gpt-6.1-sol", "gpt-6-astra").any { model == it || model.startsWith("$it-") }
            put("effort", if (needsLow) "low" else "none")
        }
        putJsonObject("text") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("name", request.name)
                put("strict", true)
                put("schema", JsonObject(request.schema - "propertyOrdering"))
            }
        }
    }

    internal companion object {
        const val BASE = "https://api.openai.com/v1"

        /** The text of the output message. Throws [CheckFailure]; TooLong when it was cut off. */
        fun answer(payload: String): String = readResponse(payload) { root ->
            val status = root["status"]?.jsonPrimitive?.contentOrNull
            if (status == "incomplete") {
                val why = root["incomplete_details"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
                // Half a JSON object would only fail as "not in the expected format", and again on retry.
                if (why == "max_output_tokens") throw truncated()
                if (why == "content_filter") throw declined()
                throw CheckFailure(CheckFailure.Reason.BadResponse, why, userMessage = CheckFailure.Message.IncompleteReply)
            }
            if (status != "completed") {
                throw CheckFailure(CheckFailure.Reason.BadResponse, errorMessage(payload), userMessage = CheckFailure.Message.IncompleteReply)
            }
            val parts = root["output"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .filter { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
                .flatMap { it["content"]?.jsonArray.orEmpty() }
                .map { it.jsonObject }
            if (parts.any { it["type"]?.jsonPrimitive?.contentOrNull == "refusal" }) throw declined()
            parts.filter { it["type"]?.jsonPrimitive?.contentOrNull == "output_text" }
                .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
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
