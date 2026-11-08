package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.llm.Deadline
import com.evanaronson.linguize.llm.DroppableOption
import com.evanaronson.linguize.llm.JSON_MEDIA_TYPE
import com.evanaronson.linguize.llm.OpenAIClient
import com.evanaronson.linguize.llm.forProviderRequests
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI's Responses API with the image as an input_image data URL. Same endpoint, headers,
 * redirect policy and answer reading as [OpenAIClient]; only the request body differs.
 */
class OpenAIVisionClient(http: OkHttpClient) : VisionClient {
    private val http = http.forProviderRequests()

    /** Low reasoning effort; models that reject the option get their default instead. */
    private val lowReasoning = DroppableOption(refusal = "reasoning")

    override suspend fun generate(key: String, model: String, request: MultimodalRequest): String {
        val deadline = Deadline(http)
        val (code, payload) = lowReasoning.send(model) { withReasoning ->
            val call = Request.Builder()
                .url("${OpenAIClient.BASE}/responses")
                .header("Authorization", "Bearer $key")
                .post(body(model, request, withReasoning).toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            deadline.send(call)
        }
        if (code != 200) throw OpenAIClient.failure(code, payload)
        return OpenAIClient.answer(payload)
    }

    internal companion object {
        fun body(model: String, request: MultimodalRequest, lowReasoning: Boolean): JsonObject = buildJsonObject {
            put("model", model)
            put("instructions", request.system)
            putJsonArray("input") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject { put("type", "input_text"); put("text", request.user) })
                            add(
                                buildJsonObject {
                                    put("type", "input_image")
                                    put("image_url", "data:${request.image.mimeType};base64,${request.image.base64()}")
                                    // Small print on letters and notices needs the full-resolution tiles.
                                    put("detail", "high")
                                },
                            )
                        }
                    },
                )
            }
            put("store", false)
            put("max_output_tokens", request.maxTokens)
            if (lowReasoning) putJsonObject("reasoning") { put("effort", "low") }
            putJsonObject("text") {
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("name", request.name)
                    put("strict", true)
                    put("schema", JsonObject(request.schema - "propertyOrdering"))
                }
            }
        }
    }
}
