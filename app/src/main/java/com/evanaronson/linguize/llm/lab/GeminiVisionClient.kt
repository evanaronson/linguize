package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.llm.Deadline
import com.evanaronson.linguize.llm.DroppableOption
import com.evanaronson.linguize.llm.GeminiClient
import com.evanaronson.linguize.llm.JSON_MEDIA_TYPE
import com.evanaronson.linguize.llm.forProviderRequests
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Gemini's generateContent with the image as an inlineData part. Same endpoint, headers,
 * redirect policy and answer reading as [GeminiClient]; only the request body differs.
 */
class GeminiVisionClient(http: OkHttpClient) : VisionClient {
    private val http = http.forProviderRequests()

    /** Low thinking: reading a page needs some, but the person is waiting. Dropped if refused. */
    private val lowThinking = DroppableOption(refusal = "thinking")

    override suspend fun generate(key: String, model: String, request: MultimodalRequest): String {
        val deadline = Deadline(http)
        val (code, payload) = lowThinking.send(model) { withThinking ->
            val call = Request.Builder()
                .url("${GeminiClient.BASE}/models".toHttpUrl().newBuilder().addPathSegment("$model:generateContent").build())
                .header("x-goog-api-key", key)
                .post(body(request, withThinking).toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            deadline.send(call)
        }
        if (code != 200) throw GeminiClient.failure(code, payload)
        return GeminiClient.answer(payload)
    }

    internal companion object {
        fun body(request: MultimodalRequest, lowThinking: Boolean): JsonObject = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", request.system) }) }
            }
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            // Image first: Google recommends it for a single-image prompt.
                            add(
                                buildJsonObject {
                                    putJsonObject("inlineData") {
                                        put("mimeType", request.image.mimeType)
                                        put("data", request.image.base64())
                                    }
                                },
                            )
                            add(buildJsonObject { put("text", request.user) })
                        }
                    },
                )
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseJsonSchema", request.schema)
                put("maxOutputTokens", request.maxTokens)
                if (lowThinking) putJsonObject("thinkingConfig") { put("thinkingLevel", "LOW") }
            }
        }
    }
}
