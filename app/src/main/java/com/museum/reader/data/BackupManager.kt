package com.museum.reader.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.museum.reader.model.AudioKind
import com.museum.reader.model.ReaderSettings
import com.museum.reader.model.SentenceAudio
import com.museum.reader.cloud.CloudProvider
import com.museum.reader.cloud.CloudSpeechPreferences
import com.museum.reader.cloud.CloudSpeechPreferencesRepository
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

class PreparedBackup internal constructor(
    val preview: BackupPreview,
    internal val graph: BackupGraph,
    internal val directory: File,
    internal val audio: List<SentenceAudio>,
)

/** Stages untrusted packages and commits graph references only after immutable audio files exist. */
class BackupManager(
    private val context: Context,
    private val repository: DocumentRepository,
    private val checkpoint: ((String) -> Unit)? = null,
) {
    private val stagingRoot get() = File(context.filesDir, "restore-staging")
    private val contentResolver get() = context.contentResolver

    suspend fun export(uri: Uri, settings: ReaderSettings) {
        val temporary = File(context.cacheDir, "backup-export-" + UUID.randomUUID() + ".zip")
        try {
            writePackage(temporary, settings)
            val output = contentResolver.openOutputStream(uri, "w") ?: error("无法写入所选位置")
            output.buffered().use { target -> temporary.inputStream().buffered().use { it.copyTo(target) } }
        } finally {
            temporary.delete()
        }
    }

    private suspend fun writePackage(target: File, settings: ReaderSettings) {
        require(File(context.filesDir, "recording-pending").listFiles().orEmpty().none { it.extension == "json" }) {
            "有待确认录音，请先在录音或存储维护中保存、放弃后再备份，避免漏掉本次录音"
        }
        val snapshot = repository.exportPackageData(settings)
        val root = snapshot.manifest.put("packageVersion", 3)
        val cloud = CloudSpeechPreferencesRepository(context).load()
        root.put("cloudPreferences", JSONObject().put("enabled", cloud.enabled)
            .put("primaryProvider", cloud.primaryProvider.name).put("xunfeiVoice", cloud.xunfeiVoice)
            .put("baiduVoice", cloud.baiduVoice).put("dailyCharacterLimit", cloud.dailyCharacterLimit))
        val descriptors = JSONArray()
        val sources = LinkedHashMap<String, File>()
        var total = 0L
        for (asset in snapshot.audio) {
            val file = AudioFiles.requireOwned(context, File(asset.path))
            require(file.isFile) { "有音频文件已缺失，请先在存储管理中检查后再备份" }
            val size = file.length()
            require(size in 1..MAX_AUDIO_BYTES) { "有音频超过 100 MB，无法安全备份" }
            total += size
            require(total <= MAX_TOTAL_BYTES) { "备份音频超过 2 GB，请先整理缓存" }
            val extension = file.extension.lowercase().takeIf { it in EXTENSIONS } ?: "audio"
            val entry = "audio/" + stableId(asset.id) + "." + extension
            val descriptor = JSONObject().put("id", asset.id).put("documentId", asset.documentId)
                .put("sentenceId", asset.sentenceId).put("kind", asset.kind.name)
                .putNullable("provider", asset.provider).putNullable("voiceId", asset.voiceId)
                .put("textHash", asset.textHash).put("durationMs", asset.durationMs)
                .putNullable("archivedAt", asset.archivedAt).put("entry", entry)
                .put("bytes", size).put("sha256", AudioFiles.sha256(file))
            descriptors.put(descriptor)
            sources[entry] = file
        }
        require(sources.size + 1 <= MAX_ENTRIES) { "备份文件数量超限" }
        root.put("audio", descriptors)
        val manifestBytes = root.toString().toByteArray(Charsets.UTF_8)
        require(manifestBytes.size <= MAX_MANIFEST_BYTES) { "备份清单超过 30 MB，请先整理文稿历史" }
        require(context.cacheDir.usableSpace > total + manifestBytes.size + 16L * 1024 * 1024) { "手机空间不足以生成安全备份" }
        check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建备份目录" }
        FileOutputStream(target).use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifestBytes)
                zip.closeEntry()
                for (i in 0 until descriptors.length()) {
                    val item = descriptors.getJSONObject(i)
                    val entry = item.getString("entry")
                    val source = sources.getValue(entry)
                    zip.putNextEntry(ZipEntry(entry))
                    source.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                    require(source.length() == item.getLong("bytes") && AudioFiles.sha256(source) == item.getString("sha256")) {
                        "备份期间音频发生变化，请停止录音或生成后重试"
                    }
                }
                zip.finish()
                zip.flush()
                output.fd.sync()
            }
        }
    }

    suspend fun prepare(uri: Uri): PreparedBackup {
        val directory = File(stagingRoot, UUID.randomUUID().toString())
        check(directory.mkdirs()) { "无法创建恢复暂存目录" }
        try {
            val entries = LinkedHashMap<String, File>()
            var manifest: String? = null
            val input = contentResolver.openInputStream(uri)?.let(::BufferedInputStream) ?: error("无法读取备份")
            input.use { stream ->
                stream.mark(4)
                val isZip = stream.read() == 'P'.code && stream.read() == 'K'.code
                stream.reset()
                if (!isZip) {
                    manifest = readLimited(stream, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
                } else {
                    val seen = HashSet<String>()
                    var total = 0L
                    ZipInputStream(stream).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            require(seen.add(entry.name) && seen.size <= MAX_ENTRIES) { "备份包含重复条目或文件过多" }
                            when {
                                entry.name == "audio/" && entry.isDirectory -> Unit
                                entry.name == "manifest.json" && !entry.isDirectory ->
                                    manifest = readLimited(zip, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
                                !entry.isDirectory && entry.name.matches(Regex("audio/[A-Za-z0-9._-]+")) &&
                                    entry.name.substringAfter('/').let { it != "." && it != ".." } -> {
                                    val target = File(directory, UUID.randomUUID().toString() + ".asset")
                                    var size = 0L
                                    FileOutputStream(target).use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            val count = zip.read(buffer)
                                            if (count < 0) break
                                            size += count
                                            total += count
                                            require(size <= MAX_AUDIO_BYTES && total <= MAX_TOTAL_BYTES) { "备份解压音频超过安全上限" }
                                            output.write(buffer, 0, count)
                                        }
                                        output.fd.sync()
                                    }
                                    entries[entry.name] = target
                                }
                                else -> error("备份包含不支持或不安全的条目")
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
            }
            val graph = repository.parseBackup(manifest ?: error("备份缺少清单"))
            graph.root.optJSONObject("cloudPreferences")?.let(::parseCloudPreferences)
            require(graph.root.optInt("packageVersion", 1) in 1..3) { "备份包版本过新，请升级应用" }
            val sentences = graph.documents.flatMap { it.sentences }.associateBy { it.id }
            val titles = graph.documents.associate { it.document.id to it.document.title }
            val warnings = ArrayList<String>()
            val audio = ArrayList<SentenceAudio>()
            val ids = HashSet<String>()
            val usedEntries = HashSet<String>()
            val descriptors = graph.root.optJSONArray("audio") ?: JSONArray()
            require(descriptors.length() <= MAX_ENTRIES - 1) { "音频清单数量超限" }
            for (i in 0 until descriptors.length()) {
                val item = descriptors.getJSONObject(i)
                val id = item.requiredId("id")
                val documentId = item.requiredId("documentId")
                val sentenceId = item.requiredId("sentenceId")
                require(ids.add(id)) { "音频编号重复" }
                val sentence = sentences[sentenceId] ?: error("音频引用不存在的句子")
                require(sentence.documentId == documentId && item.getString("textHash") == sentence.textHash) { "音频文字或所属文稿校验失败" }
                val entry = item.getString("entry")
                require(entry.matches(Regex("audio/[A-Za-z0-9._-]+")) && usedEntries.add(entry)) { "音频路径无效或重复" }
                val file = entries[entry]
                val label = titles[documentId].orEmpty() + " 第" + (sentence.ordinal + 1) + "句"
                if (file == null) {
                    warnings += label + "：备份中缺少音频文件"
                    continue
                }
                if (graph.root.optInt("packageVersion", 1) >= 3) {
                    require(item.has("sha256") && item.has("bytes")) { "新格式音频缺少完整性校验" }
                    if (file.length() != item.getLong("bytes") || AudioFiles.sha256(file) != item.getString("sha256")) {
                        warnings += label + "：音频完整性校验失败，未恢复此音频"
                        continue
                    }
                }
                val info = AudioFiles.inspect(file)
                if (info == null) {
                    warnings += label + "：音频无法解码，未恢复此音频"
                    continue
                }
                audio += SentenceAudio(id, documentId, sentenceId, AudioKind.valueOf(item.getString("kind")),
                    item.nullableString("provider"), item.nullableString("voiceId"), file.path, sentence.textHash,
                    info.durationMs, item.nullableLong("archivedAt"))
            }
            require(entries.keys.all { it in usedEntries }) { "备份有未在清单登记的音频" }
            AudioFiles.writeAtomic(File(directory, "manifest.json"), graph.root.toString())
            AudioFiles.writeAtomic(File(directory, "journal.json"), JSONObject().put("id", directory.name).put("phase", "prepared").toString())
            val databasePreview = repository.previewBackup(graph, audio)
            val preview = databasePreview.copy(audioCount = audio.size, warnings = (warnings + databasePreview.warnings).distinct())
            return PreparedBackup(preview, graph, directory, audio)
        } catch (error: Throwable) {
            removeStaging(directory)
            throw error
        }
    }

    suspend fun restore(
        prepared: PreparedBackup,
        policy: BackupConflictPolicy = BackupConflictPolicy.KEEP_LOCAL,
        restoreSettings: Boolean = false,
        acceptWarnings: Boolean = false,
    ): BackupImportResult {
        require(prepared.preview.warnings.isEmpty() || acceptWarnings) { "请先确认不能完整恢复的内容提示" }
        val directory = requireStaging(prepared.directory)
        val operationId = directory.name
        if (repository.hasRestoreReceipt(operationId)) {
            recoverInterruptedRestores()
            return BackupImportResult(0, null, warnings = listOf("这次恢复已经提交，没有重复导入"))
        }
        require(repository.previewBackup(prepared.graph, prepared.audio).localFingerprint == prepared.preview.localFingerprint) {
            "本机文稿已变化，请重新选择备份并预览"
        }
        var recoveryPoint: String? = null
        if (policy == BackupConflictPolicy.REPLACE && prepared.preview.conflictTitles.isNotEmpty()) {
            val point = File(context.filesDir, "recovery-points/" + UUID.randomUUID() + ".museumreader")
            writePackage(point, SettingsRepository(context).load())
            recoveryPoint = point.path
        }
        val destination = AudioFiles.requireOwned(context, File(context.filesDir, "sentence-audio/imported/" + operationId))
        require(!destination.exists()) { "恢复暂存已存在，请重新预览" }
        val journal = JSONObject().put("id", operationId).put("phase", "staging")
            .put("destination", destination.path).put("restoreSettings", restoreSettings)
        AudioFiles.writeAtomic(File(directory, "journal.json"), journal.toString())
        try {
            check(destination.mkdirs()) { "无法创建音频恢复目录" }
            val copied = prepared.audio.map { audio ->
                val target = File(destination, stableId(audio.id) + ".audio")
                require(!target.exists()) { "恢复音频编号冲突" }
                FileOutputStream(target).use { output ->
                    File(audio.path).inputStream().use { it.copyTo(output) }
                    output.fd.sync()
                }
                audio.copy(path = target.path)
            }
            checkpoint?.invoke("files_staged")
            checkpoint?.invoke("before_commit")
            val result = repository.restoreBackup(prepared.graph, policy, copied, operationId,
                prepared.preview.localFingerprint, restoreSettings, acceptRouteWarnings = acceptWarnings)
            checkpoint?.invoke("after_commit")
            if (restoreSettings) applySettings(prepared.graph)
            journal.put("phase", "settings_applied")
            AudioFiles.writeAtomic(File(directory, "journal.json"), journal.toString())
            cleanupUnreferenced(destination)
            removeStaging(directory)
            return result.copy(warnings = (prepared.preview.warnings + result.warnings).distinct(), recoveryPoint = recoveryPoint)
        } catch (error: Throwable) {
            if (!repository.hasRestoreReceipt(operationId)) {
                cleanupUnreferenced(destination)
                removeStaging(directory)
            }
            throw error
        }
    }

    suspend fun import(uri: Uri): BackupImportResult {
        val prepared = prepare(uri)
        require(prepared.preview.conflictTitles.isEmpty() && prepared.preview.warnings.isEmpty()) {
            "此备份有冲突或缺失音频，需要先预览确认"
        }
        return restore(prepared, restoreSettings = true)
    }

    fun discard(prepared: PreparedBackup) = removeStaging(prepared.directory)

    suspend fun recoverInterruptedRestores(): List<String> {
        val messages = ArrayList<String>()
        for (directory in stagingRoot.listFiles().orEmpty().filter { it.isDirectory }) {
            try {
                requireStaging(directory)
                val journalFile = File(directory, "journal.json")
                if (!journalFile.isFile) {
                    removeStaging(directory)
                    continue
                }
                val journal = JSONObject(AtomicFile(journalFile).readFully().toString(Charsets.UTF_8))
                require(journal.getString("id") == directory.name) { "恢复记录编号不一致" }
                val committed = repository.hasRestoreReceipt(directory.name)
                if (committed && journal.optBoolean("restoreSettings") && journal.optString("phase") != "settings_applied") {
                    val graph = repository.parseBackup(File(directory, "manifest.json").readText())
                    applySettings(graph)
                }
                journal.nullableString("destination")?.let { cleanupUnreferenced(File(it)) }
                removeStaging(directory)
                messages += if (committed) "已完成上次中断的恢复整理" else "上次恢复未提交，本机原数据已保留"
            } catch (_: Exception) {
                messages += "有恢复整理尚未完成，暂存数据已保留，请检查存储空间"
            }
        }
        return messages
    }

    private suspend fun cleanupUnreferenced(directory: File) {
        val target = AudioFiles.requireOwned(context, directory)
        val expected = File(context.filesDir, "sentence-audio/imported").canonicalFile
        require(target.parentFile == expected) { "恢复目录不匹配" }
        val referenced = repository.allAudioAssets().mapTo(hashSetOf()) { File(it.path).canonicalPath }
        for (file in target.listFiles().orEmpty()) {
            require(file.isFile && file.parentFile?.canonicalFile == target) { "恢复目录包含异常条目" }
            if (file.canonicalPath !in referenced) check(file.delete()) { "无法移除恢复暂存音频" }
        }
        if (target.isDirectory && target.listFiles().isNullOrEmpty()) target.delete()
    }

    private fun applySettings(graph: BackupGraph) {
        graph.settings?.let { SettingsRepository(context).save(it, synchronous = true) }
        graph.root.optJSONObject("cloudPreferences")?.let {
            CloudSpeechPreferencesRepository(context).save(parseCloudPreferences(it), synchronous = true)
        }
    }

    private fun parseCloudPreferences(item: JSONObject): CloudSpeechPreferences {
        val limit = item.optInt("dailyCharacterLimit", 20_000)
        val xunfei = item.optString("xunfeiVoice", "xiaoyan")
        val baidu = item.optString("baiduVoice", "0")
        require(limit in 1_000..200_000 && xunfei.matches(Regex("[A-Za-z0-9_-]{1,80}")) &&
            baidu.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "备份中的云声音设置无效" }
        val provider = runCatching { CloudProvider.valueOf(item.optString("primaryProvider", "XUNFEI")) }
            .getOrElse { error("备份中的云服务类型不支持") }
        return CloudSpeechPreferences(item.optBoolean("enabled"), provider, xunfei, baidu, limit)
    }

    private fun requireStaging(directory: File): File {
        val target = directory.canonicalFile
        require(target.parentFile == stagingRoot.canonicalFile && runCatching { UUID.fromString(target.name) }.isSuccess) {
            "无效的恢复暂存目录"
        }
        return target
    }

    private fun removeStaging(directory: File) {
        val target = requireStaging(directory)
        if (target.exists()) check(target.deleteRecursively()) { "无法清理恢复暂存，资料已保留" }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "备份清单超过 30 MB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        const val MIME_TYPE = "application/vnd.museumreader.backup"
        private const val MAX_MANIFEST_BYTES = 30 * 1024 * 1024
        private const val MAX_AUDIO_BYTES = 100L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_ENTRIES = 100_000
        private val EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "audio")
    }
}
