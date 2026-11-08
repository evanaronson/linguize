package com.evanaronson.linguize.llm.lab

import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * An image ready to send: already downscaled and compressed. It lives only in memory for
 * one request and its retries; nothing writes it to storage.
 */
class ImageInput(val mimeType: String, val bytes: ByteArray) {
    /** Standard base64, as both Gemini's inlineData and OpenAI's data URLs expect. */
    fun base64(): String = Base64.getEncoder().encodeToString(bytes)

    override fun toString(): String = "ImageInput($mimeType, ${bytes.size} bytes)"
}

/** One structured request with text and one image. The text-only check path stays separate. */
data class MultimodalRequest(
    val system: String,
    val user: String,
    val image: ImageInput,
    val schema: JsonObject,
    val name: String,
    val maxTokens: Int,
)

/** A provider's multimodal endpoint. Returns the raw JSON answer text; throws CheckFailure. */
fun interface VisionClient {
    suspend fun generate(key: String, model: String, request: MultimodalRequest): String
}
