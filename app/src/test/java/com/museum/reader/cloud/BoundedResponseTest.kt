package com.museum.reader.cloud

import okhttp3.MediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class BoundedResponseTest {
    @Test fun unknownLengthBodyCannotExceedTheLimitAndExactLimitIsPreserved() {
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        assertArrayEquals(bytes, bytes.toResponseBody().readBounded(1024, "size limit"))
        var closed = false
        val source = object : okio.ForwardingSource(Buffer().write(ByteArray(1025))) {
            override fun close() { closed = true; super.close() }
        }
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            private val input = source.buffer()
            override fun source(): BufferedSource = input
        }
        val error = runCatching { body.readBounded(1024, "size limit") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException); assertEquals("size limit", error?.message); assertTrue(closed)
    }
}
