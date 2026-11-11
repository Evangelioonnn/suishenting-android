package com.museum.reader.cloud

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Cancellation closes the socket, including a body still being read. */
internal suspend fun <T> Call.readCancellable(read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive)
                    continuation.resumeWith(Result.failure(IOException("网络请求失败或超时，请检查网络后重试")))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use(read) }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }
