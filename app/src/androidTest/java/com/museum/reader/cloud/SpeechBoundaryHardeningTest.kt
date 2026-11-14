package com.museum.reader.cloud

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test

/** Intercepts outgoing requests locally; never proves acceptance of real cloud services. */
class SpeechBoundaryHardeningTest {
    private val credentials get() = BaiduCredentials("synthetic-app", "synthetic-key", "synthetic-secret")
    private fun response(request: okhttp3.Request, code: Int, body: ResponseBody) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic response").body(body).build()

    @Test fun chunkedBaiduRequestsPreserveSupplementaryChineseAndEmojiWithoutReplacement() = runBlocking<Unit> {
        val chunks = mutableListOf<String>()
        val text = "甲".repeat(299) + "𠮷" + "乙".repeat(299) + "🙂" + "丙。"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.contains("oauth")) response(request, 200,
                "{\"access_token\":\"synthetic-token\",\"expires_in\":3600}".toResponseBody())
            else {
                val body = request.body as FormBody
                val chunk = (0 until body.size).first { body.name(it) == "tex" }.let(body::value)
                chunks += chunk
                response(request, 200, ByteArray(300) { 1 }.toResponseBody())
            }
        }.build()
        try {
            BaiduSpeechProvider(credentials, "synthetic-device", client).synthesize(text, "0")
            assertTrue(chunks.size > 1)
            assertEquals(text, chunks.joinToString(""))
            assertTrue(chunks.all { it.length <= 300 && it.toByteArray(Charsets.UTF_8).size <= 900 })
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun synthesisHttpFailureIsClassifiedBeforeReadingAnUnreadableErrorBody() = runBlocking<Unit> {
        var bodyRead = false
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource { bodyRead = true; throw IOException("synthetic unreadable error body") }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.contains("oauth")) response(request, 200,
                "{\"access_token\":\"synthetic-token\",\"expires_in\":3600}".toResponseBody())
            else response(request, 429, body)
        }.build()
        try {
            val error = runCatching { BaiduSpeechProvider(credentials, "synthetic-device", client).synthesize("错误响应测试。", "0") }.exceptionOrNull()
            assertTrue("HTTP status must remain authoritative", error is CloudServiceException)
            assertEquals(CloudFailureKind.QUOTA, (error as CloudServiceException).kind)
            // Closing may attempt to obtain the body source; the original categorized exception must win.
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun oversizedSynthesisBodyIsRejectedBeforeReadingOrAllocatingItsDeclaredLength() = runBlocking<Unit> {
        var readCalls = 0
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = 25L * 1024 * 1024
            override fun source(): BufferedSource { readCalls++; throw IOException("synthetic body must not be read") }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.contains("oauth")) response(request, 200,
                "{\"access_token\":\"synthetic-token\",\"expires_in\":3600}".toResponseBody())
            else response(request, 200, body)
        }.build()
        try {
            val error = runCatching { BaiduSpeechProvider(credentials, "synthetic-device", client).synthesize("超限响应测试。", "0") }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("音频超过单句限制"))
            assertTrue("Only close may obtain the source; no bytes() read is allowed", readCalls <= 1)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
