package com.museum.reader.cloud

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class CancellableCallTest {
    @Test fun cancelClosesCallDuringSlowBodyReadWithinTwoSeconds() = runBlocking {
        val server = ServerSocket(0)
        val headersSent = CountDownLatch(1)
        var accepted: java.net.Socket? = null
        val worker = Thread {
            runCatching {
                server.accept().use { socket ->
                    accepted = socket
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\nhello".toByteArray())
                        flush()
                    }
                    headersSent.countDown()
                    while (socket.getInputStream().read() >= 0) { }
                }
            }
        }.apply { isDaemon = true; start() }
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val call = client.newCall(Request.Builder().url("http://127.0.0.1:" + server.localPort + "/").build())
        try {
            val job = launch { call.readCancellable { it.body.string() } }
            withContext(Dispatchers.IO) { assertTrue(headersSent.await(3, TimeUnit.SECONDS)) }
            val start = System.nanoTime()
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(call.isCanceled())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000)
        } finally {
            call.cancel(); accepted?.close(); server.close(); worker.join(1000)
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }
}
