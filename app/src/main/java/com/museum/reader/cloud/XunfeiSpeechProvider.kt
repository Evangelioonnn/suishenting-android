package com.museum.reader.cloud

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class XunfeiSpeechProvider(
    private val credentials: XunfeiCredentials,
    private val client: OkHttpClient,
) : CloudSpeechProvider {
    override val provider = CloudProvider.XUNFEI

    override suspend fun synthesize(text: String, voiceId: String): SynthesizedAudio {
        currentCoroutineContext().ensureActive()
        require(text.isNotBlank()) { "试听文字不能为空" }
        require(text.toByteArray(Charsets.UTF_8).size <= 8_000) { "讯飞单次合成文字过长，请拆分文稿" }
        val result = CompletableDeferred<SynthesizedAudio>()
        val audio = ByteArrayOutputStream()
        val request = Request.Builder().url(createSignedUrl()).build()
        val webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (result.isCancelled) { webSocket.cancel(); return }
                val payload = JSONObject()
                    .put("common", JSONObject().put("app_id", credentials.appId))
                    .put("business", JSONObject()
                        .put("aue", "lame")
                        .put("auf", "audio/L16;rate=16000")
                        .put("vcn", voiceId)
                        .put("tte", "UTF8")
                        .put("ent", "aisound"))
                    .put("data", JSONObject()
                        .put("status", 2)
                        .put("text", Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)))
                if (!webSocket.send(payload.toString())) result.completeExceptionally(IllegalStateException("讯飞连接发送失败"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    val code = json.optInt("code", -1)
                    if (code != 0) error("讯飞语音合成失败（$code），请检查账号、音色和额度")
                    val data = json.optJSONObject("data") ?: return@runCatching
                    data.optString("audio").takeIf { it.isNotBlank() }?.let {
                        require(audio.size() + it.length <= 24 * 1024 * 1024) { "讯飞音频超过单句限制" }
                        audio.write(Base64.decode(it, Base64.DEFAULT))
                    }
                    if (data.optInt("status") == 2) {
                        val bytes = audio.toByteArray()
                        require(bytes.size > 256) { "讯飞返回的音频内容为空" }
                        result.complete(SynthesizedAudio(bytes, "mp3"))
                        webSocket.close(1000, "complete")
                    }
                }.onFailure {
                    result.completeExceptionally(it)
                    webSocket.close(1002, "invalid response")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result.completeExceptionally(IllegalStateException("无法连接讯飞语音服务，请检查网络后重试"))
            }
        })
        return try {
            withTimeoutOrNull(65_000) { result.await() } ?: error("讯飞合成超时，请稍后重试")
        } finally {
            result.cancel()
            webSocket.cancel()
        }
    }

    internal fun createSignedUrl(now: Date = Date()): String {
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(now)
        val signatureOrigin = "host: $HOST\ndate: $date\nGET $PATH HTTP/1.1"
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(credentials.apiSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        val signature = Base64.encodeToString(mac.doFinal(signatureOrigin.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val authorizationOrigin = "api_key=\"${credentials.apiKey}\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$signature\""
        val authorization = Base64.encodeToString(authorizationOrigin.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "wss://$HOST$PATH?authorization=${encode(authorization)}&date=${encode(date)}&host=${encode(HOST)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private const val HOST = "tts-api.xfyun.cn"
        private const val PATH = "/v2/tts"
    }
}
