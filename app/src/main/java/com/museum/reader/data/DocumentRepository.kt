package com.museum.reader.data

import android.content.Context
import androidx.core.content.edit
import androidx.room.withTransaction
import com.museum.reader.data.db.AudioAssetEntity
import com.museum.reader.data.db.BookmarkEntity
import com.museum.reader.data.db.DocumentEntity
import com.museum.reader.data.db.DocumentVersionEntity
import com.museum.reader.data.db.PlaylistEntity
import com.museum.reader.data.db.PlaylistItemEntity
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.data.db.SentenceEntity
import com.museum.reader.domain.TextSegmenter
import com.museum.reader.domain.StableSentenceMatcher
import com.museum.reader.model.AudioKind
import com.museum.reader.model.DocumentSource
import com.museum.reader.model.ParagraphSegment
import com.museum.reader.model.ReaderBookmark
import com.museum.reader.model.ReaderDocument
import com.museum.reader.model.ReaderPlaylist
import com.museum.reader.model.ReaderSettings
import com.museum.reader.model.SegmentedText
import com.museum.reader.model.SentenceAudio
import com.museum.reader.model.SentenceSegment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class LoadedDocument(
    val document: ReaderDocument,
    val content: SegmentedText,
    val contentVersion: String = DocumentRepository.textHash(document.text),
    val display: com.museum.reader.domain.ReaderLayout? = null,
)

data class DocumentVersion(
    val id: String,
    val title: String,
    val text: String,
    val createdAt: Long,
)

data class BackupImportResult(
    val documentCount: Int,
    val settings: ReaderSettings?,
    val audioCount: Int = 0,
    val warnings: List<String> = emptyList(),
    val recoveryPoint: String? = null,
)

class DocumentRepository(
    private val context: Context,
    private val database: ReaderDatabase = ReaderDatabase.get(context),
) {
    private val dao = database.readerDao()
    private val migrationPreferences = context.getSharedPreferences("storage_migration", Context.MODE_PRIVATE)
    private val legacyPositions = context.getSharedPreferences("document_positions", Context.MODE_PRIVATE)
    private val legacyFile = File(context.filesDir, "documents.json")
    private val contentHashes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    private val recoveryChecked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun initialize() {
        if (migrationPreferences.getBoolean(MIGRATION_KEY, false)) return
        val existing = dao.documentSummaries()
        if (existing.isNotEmpty() || !legacyFile.exists()) {
            migrationPreferences.edit { putBoolean(MIGRATION_KEY, true) }
            return
        }

        val backupDirectory = File(context.filesDir, "legacy-backups").apply { mkdirs() }
        val backup = File(backupDirectory, "documents-v0.2.json")
        if (!backup.exists()) legacyFile.copyTo(backup)
        val documents = parseLegacy(legacyFile)
        database.withTransaction {
            documents.forEach { legacy ->
                val position = if (legacyPositions.contains(legacy.id)) {
                    legacyPositions.getInt(legacy.id, legacy.lastSentence)
                } else legacy.lastSentence
                val document = legacy.copy(lastSentence = position)
                dao.putDocument(document.toEntity())
                dao.putSentences(buildSentenceEntities(document.id, TextSegmenter.segment(document.text)))
            }
        }
        if (dao.allDocuments().size != documents.size) error("旧文稿迁移校验失败，原文件已保留")
        migrationPreferences.edit { putBoolean(MIGRATION_KEY, true) }
    }

    suspend fun summaries(): List<ReaderDocument> = dao.documentSummaries().map { it.toModel() }

    suspend fun all(): List<ReaderDocument> = dao.allDocuments().map { it.toModel() }

    suspend fun find(id: String): ReaderDocument? = dao.document(id)?.toModel()

    suspend fun load(id: String): LoadedDocument? {
        if (dao.rawDocument(id) == null) return null
        if (recoveryChecked.add(id)) {
            try {
                recoverLegacyAudio(id)
            } catch (error: Exception) {
                recoveryChecked.remove(id)
                throw error
            }
        }
        return database.withTransaction {
            val actual = dao.document(id)?.toModel() ?: return@withTransaction null
            val stored = dao.sentences(id)
            val content = segmentWithStableIds(actual.text, stored)
            LoadedDocument(actual, content, display = com.museum.reader.domain.ReaderLayout.build(content))
        }
    }

    suspend fun recoverLegacyAudio(id: String): AudioRecoveryReport =
        LegacyAudioRecovery(context, this).recover(id, dao.allSentences(id))

    suspend fun create(title: String, text: String, source: DocumentSource): ReaderDocument {
        checkedName(title, "文稿名称")
        require(text.length <= 1_000_000) { "文稿超过 100 万字，请拆分后导入" }
        val segmented = TextSegmenter.segment(text)
        require(segmented.normalizedText.length <= 1_000_000) { "文稿整理后超过 100 万字，请拆分后保存" }
        require(segmented.sentences.isNotEmpty()) { "没有找到可朗读的文字" }
        val now = System.currentTimeMillis()
        val document = ReaderDocument(
            id = UUID.randomUUID().toString(),
            title = title.trim().ifBlank { "未命名讲解词" },
            source = source,
            text = segmented.normalizedText,
            createdAt = now,
            updatedAt = now,
        )
        database.withTransaction {
            dao.putDocument(document.toEntity())
            dao.putSentences(buildSentenceEntities(document.id, segmented))
        }
        return document
    }

    suspend fun rename(id: String, title: String) {
        checkedName(title, "文稿名称")
        val current = dao.rawDocument(id) ?: return
        dao.updateTitle(id, title.trim().ifBlank { current.title }, System.currentTimeMillis())
    }

    suspend fun updateContent(id: String, title: String, text: String) {
        checkedName(title, "文稿名称")
        require(text.length <= 1_000_000) { "文稿超过 100 万字，请拆分后保存" }
        val segmented = TextSegmenter.segment(text)
        require(segmented.normalizedText.length <= 1_000_000) { "文稿整理后超过 100 万字，请拆分后保存" }
        require(segmented.sentences.isNotEmpty()) { "没有找到可朗读的文字" }
        database.withTransaction {
            val current = dao.document(id) ?: error("没有找到这篇文稿")
            val nextTitle = title.trim().ifBlank { current.title }
            if (current.text == segmented.normalizedText) {
                if (nextTitle != current.title) dao.updateTitle(id, nextTitle, System.currentTimeMillis())
                return@withTransaction
            }
            val existing = dao.sentences(id)
            val matches = StableSentenceMatcher.match(existing.map { it.text }, segmented.sentences.map { it.text })
            val nextSentences = segmented.sentences.map { sentence ->
                val previous = matches[sentence.index]?.let(existing::get)
                SentenceEntity(
                    id = previous?.id ?: UUID.randomUUID().toString(),
                    documentId = id,
                    ordinal = sentence.index,
                    paragraphIndex = sentence.paragraphIndex,
                    text = sentence.text,
                    textHash = previous?.textHash ?: textHash(sentence.text),
                )
            }
            val retainedIds = nextSentences.mapTo(hashSetOf()) { it.id }
            val previousPositionId = existing.getOrNull(current.lastSentence)?.id
            val nextPosition = nextSentences.indexOfFirst { it.id == previousPositionId }.takeIf { it >= 0 }
                ?: current.lastSentence.coerceIn(0, segmented.sentences.lastIndex)
            val snapshot = JSONArray().also { array ->
                existing.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text)) }
            }.toString()
            dao.putVersion(
                DocumentVersionEntity(
                    id = UUID.randomUUID().toString(),
                    documentId = id,
                    title = current.title,
                    text = current.text,
                    createdAt = System.currentTimeMillis(),
                    sentenceSnapshot = snapshot,
                ),
            )
            val now = System.currentTimeMillis()
            existing.filterNot { it.id in retainedIds }.forEach { dao.retireSentence(it.id, now) }
            // A small edit must not perform an INSERT/UPDATE pair for every retained
            // sentence. Keep all identity and relation rules, writing only changed rows.
            val previousById = existing.associateBy { it.id }
            dao.putSentences(nextSentences.filter { previousById[it.id] != it })
            dao.updateDocument(
                current.copy(
                    title = nextTitle,
                    text = segmented.normalizedText,
                    updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1),
                    lastSentence = nextPosition,
                ),
            )
            dao.trimVersions(id, VERSION_LIMIT)
        }
    }

    suspend fun draft(id: String) = dao.draft(id)
    suspend fun saveDraft(draft: com.museum.reader.data.db.EditorDraftEntity) {
        checkedName(draft.title, "草稿名称")
        require(draft.text.length <= 1_000_000) { "草稿超过 100 万字" }
        dao.putDraft(draft)
    }
    suspend fun deleteDraft(id: String) = dao.deleteDraft(id)

    suspend fun savePosition(id: String, sentence: Int) = dao.savePosition(id, sentence.coerceAtLeast(0))

    suspend fun trash(id: String) = dao.trashDocument(id, System.currentTimeMillis())

    suspend fun restore(id: String) = dao.restoreDocument(id)

    suspend fun setPinned(id: String, pinned: Boolean) {
        dao.updatePinned(id, pinned, System.currentTimeMillis())
    }

    suspend fun saveDocumentSettings(id: String, rate: Float?, sentencePause: Int?, paragraphPause: Int?) {
        dao.updateSpeechSettings(id, rate, sentencePause, paragraphPause)
    }

    suspend fun versions(id: String): List<DocumentVersion> = dao.versions(id).map {
        DocumentVersion(it.id, it.title, it.text, it.createdAt)
    }

    suspend fun restoreVersion(documentId: String, versionId: String) {
        val version = dao.versions(documentId).firstOrNull { it.id == versionId } ?: return
        database.withTransaction {
            updateContent(documentId, version.title, version.text)
            version.sentenceSnapshot?.let { payload ->
                val snapshot = JSONArray(payload)
                val active = dao.sentences(documentId)
                val known = dao.allSentences(documentId).associateBy { it.id }
                val restored = active.mapIndexed { index, sentence ->
                    val entry = snapshot.optJSONObject(index)
                    val previous = entry?.optString("id")?.let(known::get)
                    if (previous != null && previous.text == sentence.text && entry?.optString("text") == sentence.text) {
                        previous.copy(ordinal = index, paragraphIndex = sentence.paragraphIndex, retiredAt = null)
                    } else sentence
                }
                val retained = restored.mapTo(hashSetOf()) { it.id }
                active.filterNot { it.id in retained }.forEach { dao.retireSentence(it.id, System.currentTimeMillis()) }
                dao.putSentences(restored)
            }
        }
    }

    suspend fun bookmarks(documentId: String): List<ReaderBookmark> {
        val sentences = dao.sentences(documentId).associateBy { it.id }
        return dao.bookmarks(documentId).mapNotNull { bookmark ->
            sentences[bookmark.sentenceId]?.let {
                ReaderBookmark(bookmark.id, documentId, it.id, it.ordinal, it.text, bookmark.createdAt)
            }
        }
    }

    suspend fun toggleBookmark(documentId: String, sentenceId: String): Boolean {
        val exists = dao.bookmarks(documentId).any { it.sentenceId == sentenceId }
        if (exists) dao.deleteBookmark(documentId, sentenceId)
        else dao.putBookmark(BookmarkEntity(UUID.randomUUID().toString(), documentId, sentenceId, System.currentTimeMillis()))
        return !exists
    }

    suspend fun playlists(): List<ReaderPlaylist> {
        val oversized = dao.oversizedPlaylistIds().toSet()
        return dao.playlists().map { playlist ->
            ReaderPlaylist(playlist.id, playlist.title.take(MAX_NAME_CHARACTERS), dao.playlistItems(playlist.id).map { it.documentId },
                playlist.lastDocumentId, playlist.id in oversized || playlist.title.length > MAX_NAME_CHARACTERS)
        }
    }

    suspend fun savePlaylist(id: String?, title: String, documentIds: List<String>): ReaderPlaylist {
        checkedName(title, "路线名称")
        val playlistId = id ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        database.withTransaction {
            val previous = dao.playlists().firstOrNull { it.id == playlistId }
            val createdAt = previous?.createdAt ?: now
            val lastDocumentId = previous?.lastDocumentId?.takeIf { it in documentIds }
            if (previous != null && (previous.id in dao.oversizedPlaylistIds() || previous.title.length > MAX_NAME_CHARACTERS) &&
                title == previous.title.take(MAX_NAME_CHARACTERS)) {
                // Reordering an old oversized route must not silently replace its full stored name.
                dao.updatePlaylistKeepingTitle(playlistId, now, lastDocumentId)
            } else {
                dao.putPlaylist(PlaylistEntity(playlistId, title.trim().ifBlank { "讲解路线" }, createdAt, now, lastDocumentId))
            }
            dao.clearPlaylist(playlistId)
            dao.putPlaylistItems(documentIds.distinct().mapIndexed { index, documentId ->
                PlaylistItemEntity(playlistId, documentId, index)
            })
        }
        return ReaderPlaylist(playlistId, title, documentIds.distinct())
    }

    suspend fun deletePlaylist(id: String) = dao.deletePlaylist(id)

    suspend fun savePlaylistPosition(id: String, documentId: String) {
        if (dao.playlistItems(id).any { it.documentId == documentId } && dao.document(documentId) != null)
            dao.savePlaylistPosition(id, documentId)
    }

    suspend fun audioAssets(documentId: String): List<SentenceAudio> = dao.audioAssets(documentId).map { it.toModel() }

    suspend fun audioAssetsByKind(kind: AudioKind): List<SentenceAudio> = dao.audioAssetsByKind(kind.name).map { it.toModel() }

    suspend fun allAudioAssets(): List<SentenceAudio> = dao.allAudioAssets().map { it.toModel() }

    suspend fun latestAudio(sentenceId: String, kind: AudioKind): SentenceAudio? =
        dao.latestAudio(sentenceId, kind.name)?.toModel()

    suspend fun matchingAudio(sentenceId: String, kind: AudioKind, provider: String, voiceId: String): SentenceAudio? =
        dao.matchingAudio(sentenceId, kind.name, provider, voiceId)?.toModel()

    suspend fun putAudio(audio: SentenceAudio) {
        database.withTransaction {
        if (audio.archivedAt == null) dao.archivePreviousAudio(
            audio.sentenceId, audio.kind.name, audio.provider, audio.voiceId, audio.id, System.currentTimeMillis(),
        )
        dao.putAudio(
            AudioAssetEntity(
                id = audio.id,
                documentId = audio.documentId,
                sentenceId = audio.sentenceId,
                kind = audio.kind.name,
                provider = audio.provider,
                voiceId = audio.voiceId,
                path = audio.path,
                textHash = audio.textHash,
                durationMs = audio.durationMs,
                createdAt = System.currentTimeMillis(),
                archivedAt = audio.archivedAt,
            ),
        )
        }
    }

    suspend fun putAudioIfCurrent(audio: SentenceAudio, expectedContentVersion: String? = null): Boolean = database.withTransaction {
        val sentence = dao.rawActiveSentence(audio.sentenceId)
        if (expectedContentVersion != null && contentVersion(audio.documentId) != expectedContentVersion)
            return@withTransaction false
        if (sentence == null || sentence.documentId != audio.documentId || sentence.textHash != audio.textHash)
            return@withTransaction false
        putAudio(audio)
        true
    }

    suspend fun isCurrentSentence(documentId: String, sentenceId: String, hash: String,
        expectedContentVersion: String? = null): Boolean =
        dao.rawActiveSentence(sentenceId)?.let { it.documentId == documentId && it.textHash == hash &&
            (expectedContentVersion == null || contentVersion(documentId) == expectedContentVersion) } == true

    suspend fun contentVersion(id: String): String? {
        val meta = dao.rawDocument(id) ?: return null
        contentHashes[id]?.takeIf { it.first == meta.updatedAt }?.let { return it.second }
        val text = if (meta.text.isNotEmpty()) meta.text else dao.document(id)?.text ?: return null
        return textHash(text).also { contentHashes[id] = meta.updatedAt to it }
    }

    suspend fun archiveAudio(id: String) = dao.archiveAudio(id, System.currentTimeMillis())

    suspend fun deleteAudio(id: String) = dao.deleteAudio(id)

    suspend fun exportBackup(settings: ReaderSettings): String =
        BackupStore(database).export(settings).manifest.toString()

    suspend fun exportPackageData(settings: ReaderSettings): BackupExportData = BackupStore(database).export(settings)

    internal fun parseBackup(payload: String): BackupGraph = BackupStore(database).parse(payload)

    internal suspend fun previewBackup(graph: BackupGraph, audio: List<SentenceAudio> = emptyList()): BackupPreview =
        BackupStore(database).preview(graph, audio)

    internal suspend fun restoreBackup(
        graph: BackupGraph,
        policy: BackupConflictPolicy,
        audio: List<SentenceAudio>,
        operationId: String,
        expectedFingerprint: String? = null,
        restoreSettings: Boolean = true,
    ): BackupImportResult = BackupStore(database).restore(
        graph, policy, audio, operationId, expectedFingerprint, restoreSettings,
    ).also { contentHashes.clear() }

    suspend fun importBackup(
        payload: String,
        policy: BackupConflictPolicy = BackupConflictPolicy.KEEP_LOCAL,
    ): BackupImportResult = restoreBackup(parseBackup(payload), policy, emptyList(), UUID.randomUUID().toString())

    suspend fun hasRestoreReceipt(id: String): Boolean = dao.hasRestoreReceipt(id)

    private fun parseLegacy(file: File): List<ReaderDocument> {
        return runCatching {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        ReaderDocument(
                            id = item.getString("id"),
                            title = item.getString("title"),
                            source = runCatching { DocumentSource.valueOf(item.getString("source")) }.getOrDefault(DocumentSource.TXT),
                            text = item.getString("text"),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
                            lastSentence = item.optInt("lastSentence", 0),
                        ),
                    )
                }
            }
        }.getOrElse { throw IllegalStateException("旧文稿文件无法读取，已保留原文件供恢复", it) }
    }

    private fun buildSentenceEntities(documentId: String, content: SegmentedText): List<SentenceEntity> =
        content.sentences.map { sentence ->
            SentenceEntity(
                id = UUID.randomUUID().toString(),
                documentId = documentId,
                ordinal = sentence.index,
                paragraphIndex = sentence.paragraphIndex,
                text = sentence.text,
                textHash = textHash(sentence.text),
            )
        }

    private fun segmentWithStableIds(text: String, stored: List<SentenceEntity>): SegmentedText {
        val segmented = TextSegmenter.segment(text)
        val ordered = stored.sortedBy { it.ordinal }
        if (ordered.isNotEmpty() && ordered.map { it.text } != segmented.sentences.map { it.text } &&
            ordered.groupBy { it.paragraphIndex }.values.joinToString("\n\n") { group ->
                group.joinToString("") { it.text }
            } == text) {
            return SegmentedText(text, ordered.groupBy { it.paragraphIndex }.map { (index, group) ->
                ParagraphSegment(index, group.first().ordinal, group.map {
                    SentenceSegment(it.ordinal, index, it.text, it.id)
                })
            })
        }
        val byOrdinal = stored.associateBy { it.ordinal }
        val paragraphs = segmented.paragraphs.map { paragraph ->
            ParagraphSegment(
                index = paragraph.index,
                sentenceStart = paragraph.sentenceStart,
                sentences = paragraph.sentences.map { sentence ->
                    sentence.copy(id = byOrdinal[sentence.index]?.id.orEmpty())
                },
            )
        }
        return SegmentedText(segmented.normalizedText, paragraphs)
    }

    private fun ReaderDocument.toEntity() = DocumentEntity(
        id, title, source.name, text, createdAt, updatedAt, lastSentence, isPinned,
        speechRate, sentencePauseMs, paragraphPauseMs, null,
    )

    private fun DocumentEntity.toModel() = ReaderDocument(
        id, title, runCatching { DocumentSource.valueOf(source) }.getOrDefault(DocumentSource.TXT),
        text, createdAt, updatedAt, lastSentence, isPinned, speechRate, sentencePauseMs, paragraphPauseMs,
    )

    private fun AudioAssetEntity.toModel() = SentenceAudio(
        id, documentId, sentenceId, runCatching { AudioKind.valueOf(kind) }.getOrDefault(AudioKind.CLOUD),
        provider, voiceId, path, textHash, durationMs, archivedAt,
    )

    companion object {
        private const val MIGRATION_KEY = "room_v1_complete"
        private const val VERSION_LIMIT = 5

        fun textHash(text: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(text.trim().toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            return buildString(bytes.size * 2) {
                bytes.forEach { byte ->
                    val value = byte.toInt() and 255
                    append(hex[value ushr 4]); append(hex[value and 15])
                }
            }
        }
    }

    private data class BackupDocument(
        val document: DocumentEntity,
        val sentences: List<SentenceEntity>,
        val bookmarks: List<BookmarkEntity>,
        val versions: List<DocumentVersionEntity>,
    )
}

private fun JSONObject.optNullableDouble(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key)

private fun JSONObject.optNullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
