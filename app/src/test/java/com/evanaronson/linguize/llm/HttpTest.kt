package com.evanaronson.linguize.llm

import com.evanaronson.linguize.llm.CheckFailure.Reason
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpTest {
    @Test
    fun statusCodesMapToWhatTheCardSays() {
        val cases = listOf(
            400 to """{"error":{"message":"Bad request"}}""" to Reason.BadResponse,
            401 to """{"error":{"message":"Incorrect API key provided"}}""" to Reason.BadKey,
            403 to """{"error":{"message":"Permission denied"}}""" to Reason.BadKey,
            404 to """{"error":{"message":"models/x is not found"}}""" to Reason.BadModel,
            429 to """{"error":{"message":"Slow down"}}""" to Reason.RateLimited,
            503 to "" to Reason.Server,
            418 to "teapot" to Reason.BadResponse,
        )
        for ((call, expected) in cases) {
            val (code, payload) = call
            assertEquals("$code $payload", expected, failureFor(code, payload).reason)
        }
    }

    @Test
    fun aClientsOwnReasonWins() {
        assertEquals(Reason.BadModel, failureFor(400, "{}", Reason.BadModel).reason)
    }

    @Test
    fun theProvidersOwnMessageIsKept() {
        assertEquals("Slow down", failureFor(429, """{"error":{"message":" Slow down "}}""").detail)
        assertEquals(null, failureFor(500, "<html>").detail)
        assertEquals(null, failureFor(500, """{"error":"plain string"}""").detail)
    }

    @Test
    fun bodiesOverTheLimitAreRefused() {
        assertEquals("abc", "abc".toResponseBody(JSON_MEDIA_TYPE).readCapped(limit = 3))
        val failure = assertThrows(CheckFailure::class.java) { "abcd".toResponseBody(JSON_MEDIA_TYPE).readCapped(limit = 3) }
        assertEquals(Reason.BadResponse, failure.reason)
    }

    @Test
    fun aHugeResponseFailsTheCallInsteadOfFillingMemory() {
        val http = FakeHttp { _, _ -> 200 to "x".repeat((MAX_BODY_BYTES + 1).toInt()) }
        val failure = assertThrows(CheckFailure::class.java) {
            runBlocking { Deadline(http.client).send(Request.Builder().url("https://example.com/").build()) }
        }
        assertEquals(Reason.BadResponse, failure.reason)
    }

    @Test
    fun laterCallsGetOnlyWhatIsLeftOfTheDeadline() {
        var now = 0L
        val http = FakeHttp(callTimeoutMillis = 1_000) { _, _ -> 200 to "{}" }
        val deadline = Deadline(http.client) { now }
        val request = Request.Builder().url("https://example.com/").build()
        runBlocking { deadline.send(request) }
        now = TimeUnit.MILLISECONDS.toNanos(400)
        runBlocking { deadline.send(request) }
        assertEquals(listOf(TimeUnit.MILLISECONDS.toNanos(1_000), TimeUnit.MILLISECONDS.toNanos(600)), http.timeouts)

        now = TimeUnit.MILLISECONDS.toNanos(1_000)
        val failure = assertThrows(CheckFailure::class.java) { runBlocking { deadline.send(request) } }
        assertEquals(Reason.Timeout, failure.reason)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun modelsForOtherJobsAreLeftOutAndTheNewestComeFirst() {
        val ids = listOf("gemini-2.5-flash", "gemini-3.5-flash-lite", "gemini-embedding-001", "gemini-3.5-flash-lite", "gemini-2.5-flash-tts")
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-2.5-flash"), textModels(ids))
    }
}
