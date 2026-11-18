package com.museum.reader.recording

import android.content.Context
import com.museum.reader.data.AudioFiles
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class DamagedRecording(val name: String, val digest: String, val isolated: Boolean, val originals: List<String>)

/** Quarantine moves only the tracking record. Both filenames continue to protect raw takes. */
internal class RecordingJournals(private val context: Context) {
    private val root get() = File(context.filesDir, "recording-pending")
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun active() = records().filter { it.extension == "json" }
    fun isolated() = records().filter { it.name.endsWith(".json.quarantined") }
    fun records(): List<File> {
        if (!root.exists()) return emptyList()
        return checkNotNull(root.listFiles()) { "待确认录音目录无法读取，请检查手机存储" }
            .filter { it.isFile && (it.extension == "json" || it.name.endsWith(".json.quarantined")) }
            .sortedBy { it.name }
    }
    fun id(file: File) = file.name.removeSuffix(".quarantined").removeSuffix(".json")
    fun journal(id: String): File {
        require(uuid.matches(id)) { "待确认录音编号不合法" }
        return File(root, "$id.json")
    }
    fun read(file: File): PendingRecording {
        require(file.canonicalFile.parentFile == root.canonicalFile && file.length() in 1..65_536)
        val takeId = id(file)
        require(uuid.matches(takeId))
        val data = JSONObject(file.readText())
        val path = AudioFiles.requireOwned(context, File(data.getString("path")))
        val doc = data.getString("documentId")
        val sentence = data.getString("sentenceId")
        val hash = data.getString("textHash")
        require(doc.isNotBlank() && sentence.isNotBlank() && hash.matches(Regex("[0-9a-fA-F]{64}")))
        return PendingRecording(takeId, doc, sentence, hash, path.path, 0, false, excerpt = data.optString("excerpt").take(160))
    }
    fun matchingJournal(take: PendingRecording): File {
        val file = journal(take.id)
        require(file.isFile) { "该录音已处理，请刷新后查看" }
        val actual = read(file)
        require(actual.documentId == take.documentId && actual.sentenceId == take.sentenceId &&
            actual.textHash == take.textHash && actual.path == File(take.path).canonicalPath) { "待确认录音记录已变化，请刷新" }
        return file
    }
    // A damaged record can often still be paired with a unique capture filename. These
    // candidates are offered for export only, never used to infer a sentence association.
    fun captureIndex(): Map<String, List<File>> {
        val files = File(context.filesDir, "sentence-audio/recordings").listFiles().orEmpty()
            .flatMap { it.listFiles().orEmpty().asList() }
        return files.filter { it.isFile && it.nameWithoutExtension.takeLast(37).startsWith("-") }
            .groupBy { it.nameWithoutExtension.takeLast(36) }
    }

    fun originals(file: File, captures: Map<String, List<File>>): List<String> {
        val paths = linkedSetOf<String>()
        if (file.canonicalFile.parentFile == root.canonicalFile && file.length() in 1..65_536) runCatching {
            val raw = AudioFiles.requireOwned(context, File(JSONObject(file.readText()).getString("path")))
            if (raw.isFile) paths.add(raw.path)
        }
        val takeId = id(file)
        for (candidate in captures[takeId].orEmpty()) {
            runCatching { AudioFiles.requireOwned(context, candidate) }.getOrNull()?.let { paths += it.path }
        }
        return paths.toList()
    }

    suspend fun protectedPaths(files: List<File> = records(), linkedPath: suspend (String) -> String? = { null }): Set<String> {
        val captures by lazy { captureIndex() }
        val paths = mutableSetOf<String>()
        for (file in files) {
            val known = runCatching { read(file).path }.getOrNull()
            if (known != null) paths += known
            else {
                paths += originals(file, captures)
                linkedPath(id(file))?.let { paths += it }
            }
        }
        return paths
    }

    fun isolate(item: DamagedRecording) {
        val file = active().singleOrNull { it.name == item.name } ?: error("待确认录音记录已变化，请刷新")
        require(file.canonicalFile.parentFile == root.canonicalFile && AudioFiles.sha256(file) == item.digest &&
            runCatching { read(file) }.isFailure) { "待确认录音记录已变化，请刷新" }
        val target = File(root, file.name + ".quarantined")
        check(!target.exists()) { "待确认记录已经隔离，请刷新" }
        Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}
