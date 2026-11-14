package com.museum.reader.data

import com.museum.reader.data.db.*
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray

/** Compare the protected graph, including metadata that does not change the formal body. */
internal class BackupMetadata(private val dao: ReaderDao) {
    suspend fun conflicts(graph: BackupGraph, local: Map<String, DocumentEntity>): Set<String> = buildSet {
        for (backup in graph.documents) {
            if (backup.document.id !in local) continue
            if (!sameRelated(backup, backup.document.id, dao.allSentences(backup.document.id))) add(backup.document.id)
        }
    }

    suspend fun sameRelated(backup: BackupDocumentData, targetId: String, sentences: List<SentenceEntity>): Boolean {
        val mapping = sentenceMapping(backup, sentences)
        val expectedBookmarks = backup.bookmarks.map { listOf(mapping[it.sentenceId] ?: it.sentenceId, it.createdAt.toString()) }.sortedBy { it.toString() }
        val actualBookmarks = dao.bookmarks(targetId).map { listOf(it.sentenceId, it.createdAt.toString()) }.sortedBy { it.toString() }
        if (expectedBookmarks != actualBookmarks) return false
        if (dao.draft(targetId) != backup.draft?.copy(documentId = targetId)) return false
        val expected = backup.versions.sortedByDescending { it.createdAt }.take(5).map { historyKey(it, mapping) }.sorted()
        val actual = dao.versions(targetId).map { historyKey(it) }.sorted()
        return actual == expected
    }

    private fun sentenceMapping(backup: BackupDocumentData, local: List<SentenceEntity>): Map<String, String> =
        backup.sentences.associate { source ->
            val known = local.firstOrNull { it.id == source.id && it.textHash == source.textHash }
                ?: local.firstOrNull { source.retiredAt == null && it.retiredAt == null &&
                    it.ordinal == source.ordinal && it.textHash == source.textHash }
                ?: local.filter { it.retiredAt != null && source.retiredAt != null && it.textHash == source.textHash &&
                    it.ordinal == source.ordinal && it.paragraphIndex == source.paragraphIndex }.singleOrNull()
            source.id to (known?.id ?: source.id)
        }

    private fun historyKey(version: DocumentVersionEntity, mapping: Map<String, String> = emptyMap()): String {
        val digest = Digest()
        digest.row("version", version.title, version.text, version.createdAt,
            runCatching { mappedSnapshot(version.sentenceSnapshot, mapping) }.getOrElse { version.sentenceSnapshot })
        return digest.finish()
    }

    fun mappedSnapshot(value: String?, mapping: Map<String, String>): String? = value?.let {
        val array = JSONArray(it)
        for (index in 0 until array.length()) {
            val entry = array.getJSONObject(index)
            mapping[entry.getString("id")]?.let { id -> entry.put("id", id) }
        }
        array.toString()
    }

    suspend fun versionId(targetId: String, sourceId: String, copied: Boolean, existing: List<DocumentVersionEntity>): String {
        if (!copied && existing.any { it.id == sourceId }) return sourceId
        // Recognize IDs produced by older releases as well as exact IDs in a re-export.
        for (number in 0..100_000) {
            val suffix = if (number == 0) "" else ":collision:$number"
            val id = stableId("version:$targetId:$sourceId$suffix")
            val owner = dao.versionOwner(id)
            if (owner == null || owner == targetId) return id
        }
        error("历史版本编号无法安全映射，请重新校验备份")
    }

    suspend fun bookmarkId(targetId: String, sentenceId: String): String {
        for (number in 0..100_000) {
            val suffix = if (number == 0) "" else ":collision:$number"
            val id = stableId("bookmark:$targetId:$sentenceId$suffix")
            val existing = dao.bookmark(id)
            if (existing == null || existing.documentId == targetId && existing.sentenceId == sentenceId) return id
        }
        error("重点句编号无法安全映射，请重新校验备份")
    }

    suspend fun routeConflicts(graph: BackupGraph, localIds: Set<String>): List<String> {
        val local = dao.fullPlaylists().associateBy { it.id }
        val available = localIds + graph.documents.map { it.document.id }
        val routes = graph.root.optJSONArray("playlists") ?: return emptyList()
        return buildList {
            for (index in 0 until routes.length()) {
                val incoming = routes.getJSONObject(index)
                val old = local[incoming.getString("id")] ?: continue
                val array = incoming.optJSONArray("documentIds") ?: JSONArray()
                val ids = (0 until array.length()).map { array.getString(it) }.filter { it in available }.distinct()
                val last = incoming.nullableString("lastDocumentId")?.takeIf { it in ids }
                if (old.title != incoming.optString("title", "讲解路线") || old.lastDocumentId != last ||
                    dao.playlistItems(old.id).map { it.documentId } != ids) add(incoming.optString("title", "讲解路线"))
            }
        }
    }

    suspend fun fingerprint(documents: Collection<DocumentEntity>, audio: List<AudioAssetEntity>): String {
        val digest = Digest()
        for (doc in documents.sortedBy { it.id }) {
            // Ordinary playback position updates must not continually invalidate a modal preview.
            digest.row("document", doc.id, doc.title, doc.source, doc.text, doc.createdAt, doc.updatedAt,
                doc.isPinned, doc.speechRate, doc.sentencePauseMs, doc.paragraphPauseMs, doc.trashedAt)
            dao.allSentences(doc.id).sortedBy { it.id }.forEach {
                digest.row("sentence", it.id, it.documentId, it.ordinal, it.paragraphIndex, it.text, it.textHash, it.retiredAt)
            }
            dao.versions(doc.id).sortedBy { it.id }.forEach {
                digest.row("history", it.id, it.documentId, it.title, it.text, it.createdAt, it.sentenceSnapshot)
            }
            dao.bookmarks(doc.id).sortedBy { it.id }.forEach { digest.row("bookmark", it.id, it.documentId, it.sentenceId, it.createdAt) }
            dao.draft(doc.id)?.let { digest.row("draft", it.documentId, it.title, it.text, it.selectionStart, it.selectionEnd, it.updatedAt) }
        }
        dao.fullPlaylists().sortedBy { it.id }.forEach { route ->
            digest.row("route", route.id, route.title, route.createdAt, route.updatedAt, route.lastDocumentId)
            dao.playlistItems(route.id).forEach { digest.row("station", it.playlistId, it.documentId, it.position) }
        }
        audio.sortedBy { it.id }.forEach {
            val file = File(it.path)
            digest.row("audio", it.id, it.documentId, it.sentenceId, it.kind, it.provider, it.voiceId,
                it.path, it.textHash, it.durationMs, it.createdAt, it.archivedAt, file.length(), file.lastModified())
        }
        return digest.finish()
    }

    /** Length-prefix each field instead of building one giant, ambiguous concatenated string. */
    private class Digest {
        private val digest = MessageDigest.getInstance("SHA-256")
        fun row(vararg values: Any?) {
            digest.update(byteArrayOf(0x7f))
            for (value in values) {
                if (value == null) { digest.update(byteArrayOf(0)); continue }
                digest.update(byteArrayOf(1))
                val bytes = value.toString().toByteArray(Charsets.UTF_8)
                val size = bytes.size
                digest.update(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
                digest.update(bytes)
            }
        }
        fun finish(): String = digest.digest().joinToString("") { "%02x".format(it) }
    }
}
