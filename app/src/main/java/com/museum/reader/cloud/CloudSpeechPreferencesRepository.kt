package com.museum.reader.cloud

import android.content.Context
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CloudSpeechPreferencesRepository(context: Context) {
    private val preferences = context.getSharedPreferences("cloud_speech_settings", Context.MODE_PRIVATE)

    fun load(): CloudSpeechPreferences = CloudSpeechPreferences(
        enabled = preferences.getBoolean("enabled", false),
        primaryProvider = runCatching {
            CloudProvider.valueOf(preferences.getString("primary_provider", CloudProvider.XUNFEI.name).orEmpty())
        }.getOrDefault(CloudProvider.XUNFEI),
        xunfeiVoice = preferences.getString("xunfei_voice", "xiaoyan").orEmpty().ifBlank { "xiaoyan" },
        baiduVoice = preferences.getString("baidu_voice", "0").orEmpty().ifBlank { "0" },
        dailyCharacterLimit = preferences.getInt("daily_limit", 20_000).coerceIn(1_000, 200_000),
    )

    fun save(value: CloudSpeechPreferences, synchronous: Boolean = false) {
        val editor = preferences.edit().apply {
            putBoolean("enabled", value.enabled)
            putString("primary_provider", value.primaryProvider.name)
            putString("xunfei_voice", value.xunfeiVoice)
            putString("baidu_voice", value.baiduVoice)
            putInt("daily_limit", value.dailyCharacterLimit.coerceIn(1_000, 200_000))
        }
        if (synchronous) check(editor.commit()) { "云声音设置写入失败" } else editor.apply()
    }

    @Synchronized
    fun clientId(): String {
        preferences.getString("local_client_id", null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        check(preferences.edit().putString("local_client_id", id).commit()) { "无法保存本机声音标识，请检查存储空间" }
        return id
    }

    fun usedToday(): Int = preferences.getInt("usage_${todayKey()}", 0)

    @Synchronized
    fun addUsage(characters: Int) {
        val key = "usage_${todayKey()}"
        preferences.edit { putInt(key, preferences.getInt(key, 0) + characters.coerceAtLeast(0)) }
    }

    private fun todayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
}
