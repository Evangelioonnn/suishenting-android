package com.museum.reader.data

import android.content.Context
import com.museum.reader.cloud.CloudSpeechPreferences
import com.museum.reader.cloud.CloudSpeechPreferencesRepository
import org.json.JSONObject

/** Shared by normal preferences writes and conditional restore writes. No credentials are read. */
internal object SettingsAccess { val lock = Any() }

internal fun CloudSpeechPreferences.toBackupJson() = JSONObject().put("enabled", enabled)
    .put("primaryProvider", primaryProvider.name).put("xunfeiVoice", xunfeiVoice)
    .put("baiduVoice", baiduVoice).put("dailyCharacterLimit", dailyCharacterLimit)

internal data class BackupSettingsSnapshot(val reader: String, val cloud: String) {
    fun toJson() = JSONObject().put("reader", reader).put("cloud", cloud)
    companion object {
        fun parse(value: JSONObject) = BackupSettingsSnapshot(value.getString("reader"), value.getString("cloud"))
    }
}

internal class BackupSettingsGuard(context: Context) {
    private val reader = SettingsRepository(context)
    private val cloud = CloudSpeechPreferencesRepository(context)
    fun snapshot(): BackupSettingsSnapshot = synchronized(SettingsAccess.lock) {
        BackupSettingsSnapshot(DocumentRepository.textHash(reader.load().toBackupJson().toString()),
            DocumentRepository.textHash(cloud.load().toBackupJson().toString()))
    }
    fun requireUnchanged(expected: BackupSettingsSnapshot) {
        require(snapshot() == expected) { "本机设置已变化，请重新选择备份并预览" }
    }
    fun applyIfUnchanged(expected: BackupSettingsSnapshot?, readerTarget: com.museum.reader.model.ReaderSettings?,
        cloudTarget: CloudSpeechPreferences?): Boolean = synchronized(SettingsAccess.lock) {
        val current = snapshot()
        val targetReader = readerTarget?.let { DocumentRepository.textHash(it.toBackupJson().toString()) }
        val targetCloud = cloudTarget?.let { DocumentRepository.textHash(it.toBackupJson().toString()) }
        // A previously applied target is accepted when completing a process-interrupted restore.
        if (expected != null && ((current.reader != expected.reader && current.reader != targetReader) ||
                (current.cloud != expected.cloud && current.cloud != targetCloud))) return@synchronized false
        readerTarget?.let { reader.save(it, synchronous = true) }
        cloudTarget?.let { cloud.save(it, synchronous = true) }
        true
    }
}
