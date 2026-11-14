package com.museum.reader.cloud

import java.io.ByteArrayOutputStream
import okhttp3.ResponseBody

/** Bound declared and chunked responses before allocation; cancellation still closes the call. */
internal fun ResponseBody.readBounded(limit: Int, guidance: String): ByteArray {
    require(contentLength() <= limit) { guidance }
    val output = ByteArrayOutputStream()
    byteStream().use { input ->
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { guidance }
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}
