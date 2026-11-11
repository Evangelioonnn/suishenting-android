package com.museum.reader.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class CloudFailureClassificationTest {
    @Test fun tokenHttpFailureIsClassifiedBeforeParsingANonJsonBody() = runBlocking<Unit> {
        for (status in listOf(401, 403, 429, 503)) {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                    .message("synthetic response").body("<html>synthetic private-server-body</html>".toResponseBody()).build()
            }.build()
            val provider = BaiduSpeechProvider(BaiduCredentials("synthetic-app", "synthetic-key", "synthetic-secret"), "synthetic-device", client)
            try { provider.synthesize("合成错误分类测试。", "0"); fail("Expected HTTP failure") }
            catch (error: CloudServiceException) {
                assertEquals(when(status) { 401, 403 -> CloudFailureKind.AUTHENTICATION; 429 -> CloudFailureKind.QUOTA; else -> CloudFailureKind.SERVICE }, error.kind)
                assertFalse(error.message.orEmpty().contains("private-server-body"))
                assertTrue(error.message.orEmpty().contains(status.toString()))
            }
            assertEquals(1, calls)
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
}
