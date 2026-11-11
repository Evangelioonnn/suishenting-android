package com.museum.reader.data

import android.content.Context
import com.museum.reader.cloud.BAIDU_VOICES
import com.museum.reader.cloud.XUNFEI_VOICES
import com.museum.reader.data.db.SentenceEntity
import com.museum.reader.model.AudioKind
import com.museum.reader.model.SentenceAudio
import java.io.File
import java.util.UUID

data class AudioRecoveryReport(val recovered: Int, val unmatched: List<File>)

internal class LegacyAudioRecovery(
    private val context: Context,
    private val repository: DocumentRepository,
) {
    suspend fun recover(documentId: String, sentences: List<SentenceEntity>): AudioRecoveryReport {
        val known = sentences.associateBy { it.id }
        val assets = repository.audioAssets(documentId)
        val referenced = assets.mapTo(hashSetOf()) { File(it.path).canonicalPath }
        fun key(sentenceId: String, kind: AudioKind, provider: String?, voice: String?) =
            listOf(sentenceId, kind.name, provider.orEmpty(), voice.orEmpty()).joinToString("\u0000")
        val activeKeys = assets.filter { it.archivedAt == null && File(it.path).isFile }
            .mapTo(hashSetOf()) { key(it.sentenceId, it.kind, it.provider, it.voiceId) }
        val pendingJournals = File(context.filesDir, "recording-pending").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
        val pendingIds = pendingJournals.map { it.nameWithoutExtension }
        val pendingPaths = pendingJournals.mapNotNull { journal -> runCatching {
            AudioFiles.requireOwned(context, File(org.json.JSONObject(journal.readText()).getString("path"))).path
        }.getOrNull() }.toSet()
        val unmatched = ArrayList<File>()
        var recovered = 0
        for (kindDirectory in listOf("recordings", "cloud")) {
            val directory = AudioFiles.requireOwned(context, File(context.filesDir, "sentence-audio/" + kindDirectory + "/" + documentId))
            for (file in directory.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }.sortedByDescending { it.lastModified() }) {
                if (file.canonicalPath in referenced || file.canonicalPath in pendingPaths ||
                    pendingIds.any { file.nameWithoutExtension.endsWith("-" + it) }) continue
                val name = file.nameWithoutExtension
                val sentence = known[name.take(36)]?.takeIf { name.startsWith(it.id + "-") }
                if (sentence == null) { unmatched += file; continue }
                val tail = name.removePrefix(sentence.id + "-")
                val hash = sentence.textHash.take(12)
                val details = when {
                    kindDirectory == "recordings" && tail.startsWith("recording-" + hash) &&
                        (tail == "recording-" + hash || tail.startsWith("recording-" + hash + "-")) ->
                        Triple(AudioKind.RECORDING, "local", "self")
                    kindDirectory == "cloud" && tail.startsWith("xunfei-") -> {
                        val voice = tail.removePrefix("xunfei-").substringBefore("-" + hash, "")
                        if (XUNFEI_VOICES.any { it.id == voice } && (tail.endsWith("-" + hash) || tail.contains("-" + hash + "-")))
                            Triple(AudioKind.CLOUD, "XUNFEI", voice) else null
                    }
                    kindDirectory == "cloud" && tail.startsWith("baidu-") -> {
                        val voice = tail.removePrefix("baidu-").substringBefore("-" + hash, "")
                        if (BAIDU_VOICES.any { it.id == voice } && (tail.endsWith("-" + hash) || tail.contains("-" + hash + "-")))
                            Triple(AudioKind.CLOUD, "BAIDU", voice) else null
                    }
                    else -> null
                }
                val info = if (details != null) AudioFiles.inspect(file) else null
                if (details == null || info == null) { unmatched += file; continue }
                val (kind, provider, voice) = details
                val active = !activeKeys.add(key(sentence.id, kind, provider, voice))
                repository.putAudio(SentenceAudio(UUID.randomUUID().toString(), documentId, sentence.id, kind, provider,
                    voice, AudioFiles.requireOwned(context, file).path, sentence.textHash, info.durationMs,
                    archivedAt = if (active) System.currentTimeMillis() else null))
                referenced += file.canonicalPath
                recovered++
            }
        }
        return AudioRecoveryReport(recovered, unmatched)
    }
}
