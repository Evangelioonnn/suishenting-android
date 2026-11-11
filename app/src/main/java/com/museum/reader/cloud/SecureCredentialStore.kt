package com.museum.reader.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("encrypted_cloud_credentials", Context.MODE_PRIVATE)
    var lastReadError: String? = null
        private set

    fun load(): CloudCredentials {
        lastReadError = null
        val encrypted = preferences.getString(KEY_PAYLOAD, null) ?: return CloudCredentials()
        return runCatching {
            val parts = encrypted.split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            val json = JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
            CloudCredentials(
                xunfei = json.optJSONObject("xunfei")?.let {
                    XunfeiCredentials(it.optString("appId"), it.optString("apiKey"), it.optString("apiSecret"))
                }?.takeIf { it.isComplete },
                baidu = json.optJSONObject("baidu")?.let {
                    BaiduCredentials(it.optString("appId"), it.optString("apiKey"), it.optString("secretKey"))
                }?.takeIf { it.isComplete },
            )
        }.getOrElse {
            lastReadError = "本机云密钥无法解密，原加密资料已保留。请在设置中重新配置；本地播放仍可使用"
            CloudCredentials()
        }
    }

    fun saveXunfei(credentials: XunfeiCredentials) {
        require(credentials.isComplete) { "请完整填写讯飞的 APPID、APIKey 和 APISecret" }
        save(load().copy(xunfei = credentials))
    }

    fun saveBaidu(credentials: BaiduCredentials) {
        require(credentials.isComplete) { "请完整填写百度的 AppID、API Key 和 Secret Key" }
        save(load().copy(baidu = credentials))
    }

    fun clear(provider: CloudProvider) {
        val current = load()
        save(if (provider == CloudProvider.XUNFEI) current.copy(xunfei = null) else current.copy(baidu = null))
    }

    private fun save(credentials: CloudCredentials) {
        // Explicit reconfiguration preserves the unreadable payload for recovery.
        if (lastReadError != null) preferences.getString(KEY_PAYLOAD, null)?.let {
            check(preferences.edit().putString("unreadable_payload", it).commit()) { "无法保留原密钥资料，请检查存储空间" }
        }
        val json = JSONObject()
        credentials.xunfei?.let {
            json.put("xunfei", JSONObject().put("appId", it.appId).put("apiKey", it.apiKey).put("apiSecret", it.apiSecret))
        }
        credentials.baidu?.let {
            json.put("baidu", JSONObject().put("appId", it.appId).put("apiKey", it.apiKey).put("secretKey", it.secretKey))
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(KEY_PAYLOAD, payload).commit()) { "密钥保存失败，请检查存储空间" }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "museum_reader_cloud_credentials_v1"
        private const val KEY_PAYLOAD = "payload"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
