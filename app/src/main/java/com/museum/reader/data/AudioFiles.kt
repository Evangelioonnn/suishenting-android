package com.museum.reader.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

data class AudioFileInfo(val durationMs: Long, val mimeType: String)

object AudioFiles {
    fun requireOwned(context: Context, file: File): File {
        val root = File(context.filesDir, "sentence-audio").canonicalFile
        val target = file.canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "音频路径不在应用目录内" }
        return target
    }

    fun inspect(file: File): AudioFileInfo? {
        if (!file.isFile || file.length() <= 128) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            if (duration > 0 && mime.startsWith("audio/")) AudioFileInfo(duration, mime) else null
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hex = "0123456789abcdef"
        return buildString(64) {
            digest.digest().forEach {
                val value = it.toInt() and 255
                append(hex[value ushr 4]); append(hex[value and 15])
            }
        }
    }

    fun writeAtomic(file: File, text: String) {
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建保存目录" }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }
}
