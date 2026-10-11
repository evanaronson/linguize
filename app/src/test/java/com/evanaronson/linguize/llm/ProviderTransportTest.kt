package com.evanaronson.linguize.llm

import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ProviderTransportTest {
    @Test
    fun aRedirectNeverForwardsProviderCredentialsOrSelectedText() {
        for (provider in Provider.entries) {
            RedirectServer().use { server ->
                // Only the initial request is routed to loopback. OkHttp would follow the
                // response to a different host (localhost -> 127.0.0.1) without our policy.
                val http = OkHttpClient.Builder()
                    .proxy(Proxy.NO_PROXY)
                    .callTimeout(5, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        chain.proceed(chain.request().newBuilder().url(server.url).build())
                    }
                    .build()
                try {
                    val prompt = Prompt("", "{}")
                    val client = when (provider) {
                        Provider.Gemini -> GeminiClient(http, prompt)
                        Provider.OpenAI -> OpenAIClient(http, prompt)
                    }
                    assertThrows(CheckFailure::class.java) {
                        runBlocking { client.check("private-key", "model", CheckRequest("private text", null, Punctuation.Moderate, Judgments.Both)) }
                    }
                    assertEquals(listOf("POST /redirect HTTP/1.1"), server.requests.toList())
                } finally {
                    http.dispatcher.executorService.shutdown()
                    http.connectionPool.evictAll()
                }
            }
        }
    }

    @Test
    fun cancellingTheCoroutineCancelsTheUnderlyingHttpCall() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("").body("{}".toResponseBody(JSON_MEDIA_TYPE)).build()
        }.build()
        val call = http.newCall(Request.Builder().url("https://example.com/").build())
        val result = async(Dispatchers.Default) { call.await() }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            result.cancelAndJoin()
            assertTrue(call.isCanceled())
            assertTrue(result.isCancelled)
        } finally {
            release.countDown()
            result.cancelAndJoin()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    /** A bounded loopback-only HTTP fixture; no remote service or new test dependency. */
    private class RedirectServer : Closeable {
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val url = "http://localhost:${socket.localPort}/redirect"
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val worker = thread(isDaemon = true, name = "provider-redirect-test") {
            try {
                while (!socket.isClosed) {
                    socket.accept().use { connection ->
                        connection.soTimeout = 5_000
                        val reader = connection.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val request = reader.readLine() ?: return@use
                        requests += request
                        var bodyLength = 0
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                                bodyLength = header.substringAfter(':').trim().toInt()
                            }
                        }
                        // The fixture sends ASCII JSON; consume it before replying and closing.
                        repeat(bodyLength) { reader.read() }
                        val response = if (request.contains(" /redirect ")) {
                            "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${socket.localPort}/elsewhere\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        } else {
                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
                        }
                        connection.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                    }
                }
            } catch (error: IOException) {
                if (!socket.isClosed) throw error
            }
        }

        override fun close() {
            socket.close()
            worker.join(5_000)
        }
    }
}
