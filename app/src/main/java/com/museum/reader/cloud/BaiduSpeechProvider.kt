package com.museum.reader.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import com.museum.reader.domain.SpeechTextChunks

class BaiduSpeechProvider(
    private val credentials: BaiduCredentials,
    private val deviceId: String,
    private val client: OkHttpClient,
) : CloudSpeechProvider {
    override val provider = CloudProvider.BAIDU
    private var accessToken: String? = null
    private var tokenExpiresAt = 0L

    override suspend fun synthesize(text: String, voiceId: String): SynthesizedAudio = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "试听文字不能为空" }
        val token = accessToken?.takeIf { android.os.SystemClock.elapsedRealtime() < tokenExpiresAt }
            ?: requestToken().also { accessToken = it }
        val output = ByteArrayOutputStream()
        SpeechTextChunks.split(text, 300, 900).forEach { chunk ->
            currentCoroutineContext().ensureActive()
            val bytes = synthesizeChunk(chunk, voiceId, token)
            require(output.size().toLong() + bytes.size <= MAX_AUDIO_BYTES) { AUDIO_LIMIT_GUIDANCE }
            output.write(bytes)
        }
        val bytes = output.toByteArray()
        require(bytes.size > 256) { "百度返回的音频内容为空" }
        SynthesizedAudio(bytes, "mp3")
    }

    private suspend fun synthesizeChunk(text: String, voiceId: String, token: String): ByteArray {
        val body = FormBody.Builder(Charsets.UTF_8)
            .add("tex", text)
            .add("tok", token)
            .add("cuid", deviceId)
            .add("ctp", "1")
            .add("lan", "zh")
            .add("spd", "5")
            .add("pit", "5")
            .add("vol", "5")
            .add("per", voiceId)
            .add("aue", "3")
            .build()
        val request = Request.Builder().url(TTS_URL).post(body).build()
        return client.newCall(request).readCancellable { response ->
            if (!response.isSuccessful) throw CloudServiceException.http(provider, response.code)
            val bytes = response.body.readBounded(MAX_AUDIO_BYTES, AUDIO_LIMIT_GUIDANCE)
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.contains("json") || bytes.firstOrNull() == '{'.code.toByte()) {
                val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                val code = json?.optInt("err_no", -1) ?: -1
                if (code == 110 || code == 111) { accessToken = null; tokenExpiresAt = 0 }
                error("百度语音合成失败（$code），请检查账号、音色和额度")
            }
            require(bytes.size > 128) { "百度返回的音频内容为空" }
            bytes
        }
    }

    private suspend fun requestToken(): String {
        val body = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", credentials.apiKey)
            .add("client_secret", credentials.secretKey)
            .build()
        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        return client.newCall(request).readCancellable { response ->
            if (!response.isSuccessful) throw CloudServiceException.http(provider, response.code)
            val json = JSONObject(response.body.readBounded(64 * 1024, "云服务响应超过安全上限，请稍后重试").toString(Charsets.UTF_8))
            tokenExpiresAt = android.os.SystemClock.elapsedRealtime() +
                (json.optLong("expires_in", 3600).coerceIn(120, 2_592_000) - 60) * 1000
            json.optString("access_token").takeIf { it.isNotBlank() }
                ?: error("百度鉴权失败，请检查 API Key 与 Secret Key")
        }
    }

    companion object {
        private const val MAX_AUDIO_BYTES = 24 * 1024 * 1024
        private const val AUDIO_LIMIT_GUIDANCE = "音频超过单句限制，请拆分文稿后重试"
        private const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
        private const val TTS_URL = "https://tsn.baidu.com/text2audio"
    }
}
