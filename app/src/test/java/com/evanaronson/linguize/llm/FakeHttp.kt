package com.evanaronson.linguize.llm

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.concurrent.TimeUnit

/** An OkHttpClient that never touches the network: [respond] answers each request with a status and body. */
internal class FakeHttp(callTimeoutMillis: Long = 30_000, private val respond: (Request, Int) -> Pair<Int, String>) {
    /** The requests sent, in order. */
    val requests = mutableListOf<Request>()

    /** The call timeout each request ran with, in nanoseconds. */
    val timeouts = mutableListOf<Long>()

    val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val (code, body) = synchronized(this) {
                requests += request
                timeouts += chain.call().timeout().timeoutNanos()
                respond(request, requests.size - 1)
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .body(body.toResponseBody(JSON_MEDIA_TYPE))
                .build()
        }
        .build()

    /** The body of the [index]th request, as sent. */
    fun body(index: Int): String = Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8()
}

/** An answer that parses as a Verdict. */
internal const val VERDICT_JSON =
    """{"status":"ok","language":"Catalan","meaning":"Hi","assumptions":[],"has_errors":false,"corrected":"",""" +
        """"fixes":[],"more_natural":false,"natural":"","natural_changes":[]}"""
