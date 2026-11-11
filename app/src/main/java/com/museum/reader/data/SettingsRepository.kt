package com.museum.reader.data

import android.content.Context
import androidx.core.content.edit
import com.museum.reader.model.ReaderSettings

class SettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)

    fun load(): ReaderSettings = ReaderSettings(
        speechRate = preferences.getFloat("speech_rate", 0.95f),
        pitch = preferences.getFloat("pitch", 1f),
        fontSizeSp = preferences.getFloat("font_size", 20f),
        voiceName = preferences.getString("voice_name", null),
        sentencePauseMs = preferences.getInt("sentence_pause_ms", 300),
        paragraphPauseMs = preferences.getInt("paragraph_pause_ms", 900),
    )

    fun save(settings: ReaderSettings, synchronous: Boolean = false) {
        val editor = preferences.edit().apply {
            putFloat("speech_rate", settings.speechRate)
            putFloat("pitch", settings.pitch)
            putFloat("font_size", settings.fontSizeSp)
            putString("voice_name", settings.voiceName)
            putInt("sentence_pause_ms", settings.sentencePauseMs)
            putInt("paragraph_pause_ms", settings.paragraphPauseMs)
        }
        if (synchronous) check(editor.commit()) { "朗读设置写入失败，请检查手机存储空间" } else editor.apply()
    }
}
