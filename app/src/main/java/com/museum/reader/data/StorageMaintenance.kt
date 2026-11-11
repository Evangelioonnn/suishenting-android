package com.museum.reader.data

import android.content.Context
import androidx.room.withTransaction
import com.museum.reader.data.db.*
import com.museum.reader.model.*
import com.museum.reader.playback.PlaybackBus
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class TrashedDocumentInfo(
    val id: String, val title: String, val trashedAt: Long,
    val recordings: Int, val cloud: Int, val bytes: Long,
) {
    fun canPurge(now: Long) = now - trashedAt >= StorageMaintenance.RETENTION_MS
}
data class StoredAudioInfo(
    val audio: SentenceAudio, val documentTitle: String, val sentenceText: String,
    val bytes: Long, val old: Boolean, val protected: Boolean, val missing: Boolean,
)
data class UnlinkedAudio(val path: String, val bytes: Long, val modifiedAt: Long)
data class RecoveryPoint(val path: String, val bytes: Long, val createdAt: Long)
data class StorageOverview(
    val recordingBytes: Long = 0, val cloudBytes: Long = 0, val oldBytes: Long = 0,
    val unlinkedBytes: Long = 0, val items: List<StoredAudioInfo> = emptyList(),
    val unlinked: List<UnlinkedAudio> = emptyList(), val trash: List<TrashedDocumentInfo> = emptyList(),
    val recoveryPoints: List<RecoveryPoint> = emptyList(),
)
data class CleanupResult(val removedReferences: Int, val deletedFiles: Int, val pendingFiles: Int)

sealed interface StorageAction {
    data class Cloud(val audio: List<SentenceAudio>, val bytes: Long) : StorageAction
    data class Old(val item: StoredAudioInfo) : StorageAction
    data class Unlinked(val item: UnlinkedAudio) : StorageAction
    data class Purge(val item: TrashedDocumentInfo) : StorageAction
}

/** Database removal precedes file removal, with a durable receipt and resumable journal. */
class StorageMaintenance(
    private val context: Context,
    private val repository: DocumentRepository,
    private val database: ReaderDatabase = ReaderDatabase.get(context),
    private val fault: (String) -> Unit = {},
) {
    private val dao = database.readerDao()
    private val journals get() = File(context.filesDir, "cleanup-journal").apply { mkdirs() }

    suspend fun overview(): StorageOverview {
        val documents = dao.documentSummariesIncludingTrash().associateBy { it.id }
        val sentences = documents.keys.flatMap { dao.sentenceSummaries(it) }.associateBy { it.id }
        val audio = repository.allAudioAssets()
        val historyIds = hashSetOf<String>()
        for (id in documents.keys) for (value in dao.versionSnapshots(id)) run {
            val array = JSONArray(value)
            repeat(array.length()) { historyIds += array.getJSONObject(it).getString("id") }
        }
        val pending = pendingPaths()
        val inUse = inUsePaths()
        val items = audio.map { asset ->
            val file = runCatching { AudioFiles.requireOwned(context, File(asset.path)) }.getOrNull()
            val sentence = sentences[asset.sentenceId]
            val old = asset.archivedAt != null || sentence?.retiredAt != null ||
                sentence?.textHash != asset.textHash || file?.isFile != true
            StoredAudioInfo(asset, documents[asset.documentId]?.title ?: "不可用文稿", sentence?.text.orEmpty(),
                file?.takeIf { it.isFile }?.length() ?: 0L, old,
                documents[asset.documentId]?.trashedAt != null || (old && asset.sentenceId in historyIds) ||
                    file?.path in pending || file?.path in inUse, file?.isFile != true)
        }
        val references = items.mapNotNull { ownedPath(it.audio.path) }.toSet()
        val unlinked = ownedFiles().filter { it.path !in references && it.path !in pending }
            .map { UnlinkedAudio(it.path, it.length(), it.lastModified()) }
        var recordingBytes = 0L; var cloudBytes = 0L; var oldBytes = 0L
        items.groupBy { ownedPath(it.audio.path) ?: it.audio.path }.values.forEach { choices ->
            val bytes = choices.maxOf { it.bytes }
            when {
                choices.any { !it.old && it.audio.kind == AudioKind.RECORDING } -> recordingBytes += bytes
                choices.any { !it.old && it.audio.kind == AudioKind.CLOUD } -> cloudBytes += bytes
                else -> oldBytes += bytes
            }
        }
        val trash = documents.values.filter { it.trashedAt != null }.map { doc ->
            val assets = items.filter { it.audio.documentId == doc.id }
            TrashedDocumentInfo(doc.id, doc.title, doc.trashedAt!!,
                assets.count { it.audio.kind == AudioKind.RECORDING }, assets.count { it.audio.kind == AudioKind.CLOUD },
                assets.distinctBy { ownedPath(it.audio.path) }.sumOf { it.bytes })
        }.sortedByDescending { it.trashedAt }
        val pointsRoot = File(context.filesDir, "recovery-points").canonicalFile
        val points = pointsRoot.listFiles().orEmpty().filter {
            it.isFile && it.extension == "museumreader" && it.canonicalFile.parentFile == pointsRoot
        }.map { RecoveryPoint(it.path, it.length(), it.lastModified()) }.sortedByDescending { it.createdAt }
        return StorageOverview(recordingBytes, cloudBytes, oldBytes, unlinked.sumOf { it.bytes },
            items, unlinked, trash, points)
    }

    suspend fun restoreAudio(id: String) {
        val asset = repository.allAudioAssets().firstOrNull { it.id == id } ?: error("该音频已变化，请刷新")
        val file = AudioFiles.requireOwned(context, File(asset.path))
        require(file.path !in pendingPaths()) { "这份录音仍待确认，请在录音页试听并保存" }
        require(AudioFiles.inspect(file) != null) { "音频不可播放，请保留原文件并检查备份" }
        require(repository.putAudioIfCurrent(asset.copy(archivedAt = null))) { "所属句子已变化或文稿已删除，不能绑定到现有正文" }
    }

    suspend fun cleanupAudio(expected: List<SentenceAudio>, cloudOnly: Boolean = false): CleanupResult {
        if (cloudOnly) require(expected.all { it.kind == AudioKind.CLOUD }) { "清理云缓存不能包含本人录音" }
        val current = overview()
        val byId = current.items.associateBy { it.audio.id }
        for (asset in expected) {
            val actual = byId[asset.id] ?: error("音频已变化，请刷新后重试")
            require(actual.audio == asset) { "音频已变化，请重新确认" }
            require(!actual.protected) { "所选音频用于播放、历史、恢复区或待确认录音，请先处理后再清理" }
            if (!cloudOnly) require(actual.old) { "有效本人录音不能作为旧音频清理" }
        }
        return perform(expected, emptyList(), null)
    }

    suspend fun cleanupUnlinked(expected: UnlinkedAudio): CleanupResult {
        val file = AudioFiles.requireOwned(context, File(expected.path))
        require(file.isFile && file.length() == expected.bytes && file.lastModified() == expected.modifiedAt) {
            "待确认文件已变化，请刷新后重新确认"
        }
        require(file.path !in pendingPaths() && file.path !in inUsePaths()) { "文件仍在录音或使用中" }
        require(repository.allAudioAssets().none { ownedPath(it.path) == file.path }) { "文件已有有效关联，不能作为无引用文件清理" }
        return perform(emptyList(), listOf(file), null)
    }

    suspend fun purge(expected: TrashedDocumentInfo, now: Long = System.currentTimeMillis()): CleanupResult {
        val doc = dao.anyDocument(expected.id) ?: error("文稿已变化，请刷新")
        require(doc.trashedAt == expected.trashedAt && expected.canPurge(now)) { "恢复区至少保留 7 天，到期后才能永久清理" }
        val audio = repository.audioAssets(expected.id)
        require(audio.count { it.kind == AudioKind.RECORDING } == expected.recordings &&
            audio.count { it.kind == AudioKind.CLOUD } == expected.cloud &&
            audio.distinctBy { ownedPath(it.path) }.sumOf { ownedPath(it.path)?.let(::File)?.takeIf { file -> file.isFile }?.length() ?: 0 } == expected.bytes) {
            "恢复区内容或音频已变化，请刷新后重新确认"
        }
        require(audio.none { ownedPath(it.path) in pendingPaths() || ownedPath(it.path) in inUsePaths() }) {
            "文稿仍有待确认录音或正在使用的音频，请先处理"
        }
        return perform(audio, emptyList(), expected)
    }

    private suspend fun perform(audio: List<SentenceAudio>, extra: List<File>, purge: TrashedDocumentInfo?): CleanupResult {
        val files = (audio.map { AudioFiles.requireOwned(context, File(it.path)) } + extra)
            .distinctBy { it.path }
        require(files.none { it.path in inUsePaths() || it.path in pendingPaths() }) { "音频仍在使用，请停止播放后重试" }
        val id = UUID.randomUUID().toString()
        val journal = File(journals, "$id.json")
        val record = JSONObject().put("id", id).put("files", JSONArray(files.map { it.path }))
        AudioFiles.writeAtomic(journal, record.toString())
        try {
            fault("before_commit")
            database.withTransaction {
                val actual = repository.allAudioAssets().associateBy { it.id }
                require(audio.all { actual[it.id] == it }) { "音频已变化，请刷新后重新确认" }
                if (purge != null) {
                    val doc = dao.anyDocument(purge.id) ?: error("文稿已变化")
                    require(doc.trashedAt == purge.trashedAt &&
                        actual.values.filter { it.documentId == purge.id }.map { it.id }.toSet() == audio.map { it.id }.toSet()) {
                        "恢复区内容已变化，请刷新后重新确认"
                    }
                    dao.purgeDocument(purge.id)
                } else audio.forEach { dao.deleteAudio(it.id) }
                dao.putRestoreReceipt(RestoreReceiptEntity("cleanup:$id", System.currentTimeMillis()))
            }
            fault("after_commit")
            val (deleted, remaining) = finish(journal, record)
            return CleanupResult(audio.size, deleted, remaining)
        } catch (error: Exception) {
            if (!dao.hasRestoreReceipt("cleanup:$id")) journal.delete()
            throw error
        }
    }

    suspend fun recover(): List<String> {
        val messages = ArrayList<String>()
        for (journal in journals.listFiles().orEmpty().filter { it.extension == "json" }) {
            try {
                val record = JSONObject(journal.readText())
                require(record.getString("id") == journal.nameWithoutExtension)
                if (dao.hasRestoreReceipt("cleanup:" + record.getString("id"))) {
                    val (deleted, remaining) = finish(journal, record)
                    if (deleted > 0 || remaining > 0) messages += "上次音频清理已整理，仍有 $remaining 个文件待重试"
                } else journal.delete()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { messages += "有清理记录待检查，原文件保留，可在存储维护中重试" }
        }
        return messages
    }

    private suspend fun finish(journal: File, record: JSONObject): Pair<Int, Int> {
        val references = repository.allAudioAssets().mapNotNull { ownedPath(it.path) }.toSet()
        val protected = pendingPaths() + inUsePaths()
        val remaining = ArrayList<String>()
        var deleted = 0
        val paths = record.getJSONArray("files")
        for (index in 0 until paths.length()) {
            val file = AudioFiles.requireOwned(context, File(paths.getString(index)))
            if (file.path in references || !file.exists()) continue
            if (file.path in protected || !file.delete()) remaining += file.path else deleted++
        }
        if (remaining.isEmpty()) check(journal.delete() || !journal.exists()) { "清理记录整理失败" }
        else AudioFiles.writeAtomic(journal, record.put("files", JSONArray(remaining)).toString())
        return deleted to remaining.size
    }
    private fun ownedPath(path: String): String? = runCatching { AudioFiles.requireOwned(context, File(path)).path }.getOrNull()
    private fun inUsePaths(): Set<String> {
        val session = PlaybackBus.snapshot.value
        return if (session.isPlaying) session.audioCandidates.values.flatten().mapNotNull { ownedPath(it.path) }.toSet() else emptySet()
    }
    private fun pendingPaths(): Set<String> = File(context.filesDir, "recording-pending").listFiles().orEmpty()
        .filter { it.isFile && it.extension == "json" }.mapNotNull {
            runCatching { ownedPath(JSONObject(it.readText()).getString("path")) }.getOrNull()
        }.toSet()
    private fun ownedFiles(): List<File> {
        val root = File(context.filesDir, "sentence-audio")
        if (!root.isDirectory) return emptyList()
        val visited = hashSetOf<String>()
        return root.walkTopDown().onEnter { dir ->
            val canonical = dir.canonicalFile
            (dir == root || ownedPath(dir.path) != null) && visited.add(canonical.path)
        }.filter { it.isFile }.mapNotNull { ownedPath(it.path)?.let(::File) }.distinctBy { it.path }.toList()
    }
    companion object { const val RETENTION_MS = 7 * 24 * 60 * 60_000L }
}
