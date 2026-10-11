package com.evanaronson.linguize.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** No answer or error from a provider comes near this; anything bigger isn't one, so it isn't read into memory. */
internal const val MAX_BODY_BYTES = 2L * 1024 * 1024

/**
 * One time limit for everything a single check or listing sends, retries included: the
 * client's call timeout, counted from when the deadline is made. Without a call timeout there's no limit.
 */
internal class Deadline(private val http: OkHttpClient, private val now: () -> Long = System::nanoTime) {
    private val end: Long? = http.callTimeoutMillis.takeIf { it > 0 }?.let { now() + TimeUnit.MILLISECONDS.toNanos(it.toLong()) }

    /** Sends [request] with whatever time is left. */
    suspend fun send(request: Request): Pair<Int, String> {
        val call = http.newCall(request)
        if (end != null) {
            val left = end - now()
            if (left <= 0) throw CheckFailure(CheckFailure.Reason.Timeout)
            call.timeout().timeout(left, TimeUnit.NANOSECONDS)
        }
        return call.await()
    }
}

/** Status code and body. Runs on OkHttp's dispatcher and cancels the call if the caller goes away. */
internal suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { it.code to it.body.readCapped() }
                } catch (e: IOException) {
                    onFailure(call, e)
                    return
                } catch (e: CheckFailure) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                    return
                }
                cont.resume(result)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                // Timeouts are InterruptedIOExceptions; anything else means no usable connection.
                val reason = if (e is InterruptedIOException) CheckFailure.Reason.Timeout else CheckFailure.Reason.Offline
                cont.resumeWithException(CheckFailure(reason, cause = e))
            }
        },
    )
}

/** The body as UTF-8 (all both providers send), refusing anything over [limit] bytes without reading past it. */
internal fun ResponseBody.readCapped(limit: Long = MAX_BODY_BYTES): String {
    if (contentLength() > limit) throw tooLarge()
    val source = source()
    // Buffers up to limit + 1 bytes; true means there was more than the limit.
    if (source.request(limit + 1)) throw tooLarge()
    return source.buffer.readUtf8()
}

private fun tooLarge() = CheckFailure(CheckFailure.Reason.BadResponse, "The AI's reply was too large.")

/** Reads a provider's JSON response; anything malformed becomes a BadResponse rather than a crash. */
internal inline fun <T> readResponse(payload: String, read: (JsonObject) -> T): T = try {
    read(Json.parseToJsonElement(payload).jsonObject)
} catch (e: SerializationException) {
    throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
} catch (e: IllegalArgumentException) {
    throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
} catch (e: IllegalStateException) {
    throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
}

/**
 * Turns an HTTP error into what the card tells the user, keeping the provider's message.
 * Only what the status code means for every provider; each client passes [reason] when
 * it recognises something more specific in its own error format.
 */
internal fun failureFor(code: Int, payload: String, reason: CheckFailure.Reason? = null): CheckFailure =
    CheckFailure(reason ?: reasonFor(code), errorMessage(payload))

private fun reasonFor(code: Int): CheckFailure.Reason = when {
    code == 401 || code == 403 -> CheckFailure.Reason.BadKey
    code == 404 -> CheckFailure.Reason.BadModel
    code == 429 -> CheckFailure.Reason.RateLimited
    code >= 500 -> CheckFailure.Reason.Server
    else -> CheckFailure.Reason.BadResponse
}

/** The `error` object both Gemini and OpenAI send with a failure, or null. */
internal fun errorObject(payload: String): JsonObject? = runCatching {
    Json.parseToJsonElement(payload).jsonObject["error"]?.jsonObject
}.getOrNull()

/** The `error.message` field both Gemini and OpenAI use, trimmed, or null. */
internal fun errorMessage(payload: String): String? = runCatching {
    errorObject(payload)?.get("message")?.jsonPrimitive?.content
}.getOrNull()?.let(::scrubSecrets)?.takeIf { it.isNotEmpty() }

/** What the card says when an answer was cut off at the output limit. */
internal fun truncated(): CheckFailure = CheckFailure(
    CheckFailure.Reason.TooLong,
    "The AI's reply got cut off. A shorter text avoids this.",
)

/** The plain reason when [service] won't check a text (Gemini's SAFETY, OpenAI's content_filter). */
internal fun declined(service: String): CheckFailure =
    CheckFailure(CheckFailure.Reason.BadResponse, "$service declined to check this text.")
