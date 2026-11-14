package com.museum.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert

@Dao
interface ReaderDao {
    @Query("SELECT id, title, source, CASE WHEN length(text) > 50000 THEN '' ELSE text END AS text, createdAt, updatedAt, lastSentence, isPinned, speechRate, sentencePauseMs, paragraphPauseMs, trashedAt FROM documents WHERE trashedAt IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun rawDocuments(): List<DocumentEntity>

    @Query("SELECT id, title, source, CASE WHEN length(text) > 50000 THEN '' ELSE text END AS text, createdAt, updatedAt, lastSentence, isPinned, speechRate, sentencePauseMs, paragraphPauseMs, trashedAt FROM documents WHERE id = :id AND trashedAt IS NULL")
    suspend fun rawDocument(id: String): DocumentEntity?

    @Upsert
    suspend fun putDocument(document: DocumentEntity)

    @Query("SELECT id, title, source, CASE WHEN length(text) > 50000 THEN '' ELSE text END AS text, createdAt, updatedAt, lastSentence, isPinned, speechRate, sentencePauseMs, paragraphPauseMs, trashedAt FROM documents WHERE id = :id")
    suspend fun rawAnyDocument(id: String): DocumentEntity?

    @Query("SELECT id, title, source, CASE WHEN length(text) > 50000 THEN '' ELSE text END AS text, createdAt, updatedAt, lastSentence, isPinned, speechRate, sentencePauseMs, paragraphPauseMs, trashedAt FROM documents ORDER BY createdAt")
    suspend fun rawDocumentsIncludingTrash(): List<DocumentEntity>

    @Update
    suspend fun updateDocument(document: DocumentEntity)

    @Query("UPDATE documents SET lastSentence = :sentence WHERE id = :id")
    suspend fun savePosition(id: String, sentence: Int)

    @Query("UPDATE documents SET trashedAt = :time WHERE id = :id")
    suspend fun trashDocument(id: String, time: Long)

    @Query("UPDATE documents SET trashedAt = NULL WHERE id = :id")
    suspend fun restoreDocument(id: String)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun purgeDocument(id: String)

    @Query("SELECT id, documentId, ordinal, paragraphIndex, CASE WHEN length(text) > 50000 THEN '' ELSE text END AS text, textHash, retiredAt FROM sentences WHERE documentId = :documentId AND retiredAt IS NULL ORDER BY ordinal")
    suspend fun rawSentences(documentId: String): List<SentenceEntity>

    @Query("SELECT id, documentId, ordinal, paragraphIndex, CASE WHEN length(text) > 50000 THEN '' ELSE text END AS text, textHash, retiredAt FROM sentences WHERE documentId = :documentId ORDER BY ordinal")
    suspend fun rawAllSentences(documentId: String): List<SentenceEntity>

    @Query("SELECT s.id, s.documentId, s.ordinal, s.paragraphIndex, CASE WHEN length(s.text) > 50000 THEN '' ELSE s.text END AS text, s.textHash, s.retiredAt FROM sentences s INNER JOIN documents d ON d.id = s.documentId WHERE s.id = :id AND s.retiredAt IS NULL AND d.trashedAt IS NULL")
    suspend fun rawActiveSentence(id: String): SentenceEntity?

    @Query("SELECT id, documentId FROM sentences")
    suspend fun sentenceOwners(): List<SentenceOwner>

    @Query("UPDATE sentences SET retiredAt = :time WHERE id = :id")
    suspend fun retireSentence(id: String, time: Long)

    @Upsert
    suspend fun putSentences(sentences: List<SentenceEntity>)

    @Query("DELETE FROM sentences WHERE documentId = :documentId")
    suspend fun deleteSentences(documentId: String)

    @Query("DELETE FROM sentences WHERE id = :id")
    suspend fun deleteSentence(id: String)

    @Upsert
    suspend fun putVersion(version: DocumentVersionEntity)

    @Query("SELECT id, documentId, title, CASE WHEN length(text) > 50000 THEN '' ELSE text END AS text, createdAt, CASE WHEN length(sentenceSnapshot) > 50000 THEN '' ELSE sentenceSnapshot END AS sentenceSnapshot FROM document_versions WHERE documentId = :documentId ORDER BY createdAt DESC")
    suspend fun rawVersions(documentId: String): List<DocumentVersionEntity>

    @Query("DELETE FROM document_versions WHERE id IN (SELECT id FROM document_versions WHERE documentId = :documentId ORDER BY createdAt DESC LIMIT -1 OFFSET :keep)")
    suspend fun trimVersions(documentId: String, keep: Int)

    @Query("SELECT * FROM bookmarks WHERE documentId = :documentId")
    suspend fun bookmarks(documentId: String): List<BookmarkEntity>

    @Upsert
    suspend fun putBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE documentId = :documentId AND sentenceId = :sentenceId")
    suspend fun deleteBookmark(documentId: String, sentenceId: String)

    @Query("SELECT id, substr(title, 1, 1000) AS title, createdAt, updatedAt, lastDocumentId FROM playlists ORDER BY updatedAt DESC")
    suspend fun playlists(): List<PlaylistEntity>

    @Query("SELECT id FROM playlists WHERE length(title) > 1000")
    suspend fun oversizedPlaylistIds(): List<String>

    @Query("UPDATE playlists SET updatedAt = :time, lastDocumentId = :lastDocumentId WHERE id = :id")
    suspend fun updatePlaylistKeepingTitle(id: String, time: Long, lastDocumentId: String?)

    @Query("""SELECT EXISTS(
        SELECT 1 FROM documents WHERE length(title) > 1000
        UNION ALL SELECT 1 FROM playlists WHERE length(title) > 1000
        UNION ALL SELECT 1 FROM document_versions WHERE length(title) > 1000
        UNION ALL SELECT 1 FROM editor_drafts WHERE length(title) > 1000)""")
    suspend fun hasOversizedNames(): Boolean

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position")
    suspend fun playlistItems(playlistId: String): List<PlaylistItemEntity>

    @Upsert
    suspend fun putPlaylist(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET lastDocumentId = :documentId WHERE id = :id")
    suspend fun savePlaylistPosition(id: String, documentId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPlaylistItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Query("SELECT * FROM audio_assets WHERE documentId = :documentId")
    suspend fun audioAssets(documentId: String): List<AudioAssetEntity>

    @Query("SELECT * FROM audio_assets WHERE kind = :kind")
    suspend fun audioAssetsByKind(kind: String): List<AudioAssetEntity>

    @Query("SELECT * FROM audio_assets")
    suspend fun allAudioAssets(): List<AudioAssetEntity>

    @Query("SELECT * FROM audio_assets WHERE sentenceId = :sentenceId AND kind = :kind AND archivedAt IS NULL ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestAudio(sentenceId: String, kind: String): AudioAssetEntity?

    @Query("SELECT * FROM audio_assets WHERE sentenceId = :sentenceId AND kind = :kind AND provider = :provider AND voiceId = :voiceId AND archivedAt IS NULL ORDER BY createdAt DESC LIMIT 1")
    suspend fun matchingAudio(sentenceId: String, kind: String, provider: String, voiceId: String): AudioAssetEntity?

    @Upsert
    suspend fun putAudio(asset: AudioAssetEntity)

    @Query("UPDATE audio_assets SET archivedAt = :time WHERE sentenceId = :sentenceId AND kind = :kind AND provider IS :provider AND voiceId IS :voiceId AND id != :exceptId AND archivedAt IS NULL")
    suspend fun archivePreviousAudio(sentenceId: String, kind: String, provider: String?, voiceId: String?, exceptId: String, time: Long)

    @Query("UPDATE audio_assets SET archivedAt = :time WHERE sentenceId = :sentenceId AND kind = 'RECORDING' AND id != :exceptId AND archivedAt IS NULL")
    suspend fun archivePreviousRecording(sentenceId: String, exceptId: String, time: Long)

    @Upsert
    suspend fun putDraft(draft: EditorDraftEntity)

    @Query("SELECT documentId, title, CASE WHEN length(text) > 50000 THEN '' ELSE text END AS text, selectionStart, selectionEnd, updatedAt FROM editor_drafts WHERE documentId = :documentId")
    suspend fun rawDraft(documentId: String): EditorDraftEntity?

    @Query("DELETE FROM editor_drafts WHERE documentId = :documentId")
    suspend fun deleteDraft(documentId: String)

    @Query("SELECT documentId, title, CASE WHEN length(text) > 50000 THEN '' ELSE text END AS text, selectionStart, selectionEnd, updatedAt FROM editor_drafts")
    suspend fun rawAllDrafts(): List<EditorDraftEntity>

    @Upsert
    suspend fun putRestoreReceipt(receipt: RestoreReceiptEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM restore_receipts WHERE id = :id)")
    suspend fun hasRestoreReceipt(id: String): Boolean

    @Query("UPDATE audio_assets SET archivedAt = :time WHERE id = :id")
    suspend fun archiveAudio(id: String, time: Long)

    @Query("DELETE FROM audio_assets WHERE id = :id")
    suspend fun deleteAudio(id: String)

    @Query("SELECT id, title, source, '' AS text, createdAt, updatedAt, lastSentence, isPinned, speechRate, sentencePauseMs, paragraphPauseMs, trashedAt FROM documents WHERE trashedAt IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun documentSummaries(): List<DocumentEntity>

    @Query("SELECT id, title, source, '' AS text, createdAt, updatedAt, lastSentence, isPinned, speechRate, sentencePauseMs, paragraphPauseMs, trashedAt FROM documents ORDER BY createdAt")
    suspend fun documentSummariesIncludingTrash(): List<DocumentEntity>

    @Query("SELECT id, documentId, ordinal, paragraphIndex, substr(text, 1, 240) AS text, textHash, retiredAt FROM sentences WHERE documentId = :documentId ORDER BY ordinal")
    suspend fun sentenceSummaries(documentId: String): List<SentenceEntity>

    @Query("UPDATE documents SET title = :title, updatedAt = max(updatedAt + 1, :now) WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, now: Long)

    @Query("UPDATE documents SET isPinned = :pinned, updatedAt = max(updatedAt + 1, :now) WHERE id = :id")
    suspend fun updatePinned(id: String, pinned: Boolean, now: Long)

    @Query("UPDATE documents SET speechRate = :rate, sentencePauseMs = :sentencePause, paragraphPauseMs = :paragraphPause WHERE id = :id")
    suspend fun updateSpeechSettings(id: String, rate: Float?, sentencePause: Int?, paragraphPause: Int?)

    @Transaction
    suspend fun versionSnapshots(documentId: String): List<String> = rawVersions(documentId).mapNotNull { row ->
        if (row.sentenceSnapshot == "") chunks { versionSnapshotChunk(row.id, it) } else row.sentenceSnapshot
    }

    @Query("""SELECT
        coalesce((SELECT sum(length(CAST(text AS BLOB))) FROM documents), 0) +
        coalesce((SELECT sum(length(CAST(text AS BLOB)) + 128) FROM sentences), 0) +
        coalesce((SELECT sum(length(CAST(text AS BLOB)) + coalesce(length(CAST(sentenceSnapshot AS BLOB)), 0)) FROM document_versions), 0) +
        coalesce((SELECT sum(length(CAST(text AS BLOB))) FROM editor_drafts), 0)""")
    suspend fun backupTextLowerBound(): Long

    @Query("SELECT substr(text, :start, 40000) FROM documents WHERE id = :id")
    suspend fun documentTextChunk(id: String, start: Int): String?

    @Query("SELECT substr(text, :start, 40000) FROM sentences WHERE id = :id")
    suspend fun sentenceTextChunk(id: String, start: Int): String?

    @Query("SELECT substr(text, :start, 40000) FROM document_versions WHERE id = :id")
    suspend fun versionTextChunk(id: String, start: Int): String?

    @Query("SELECT substr(sentenceSnapshot, :start, 40000) FROM document_versions WHERE id = :id")
    suspend fun versionSnapshotChunk(id: String, start: Int): String?

    @Query("SELECT substr(text, :start, 40000) FROM editor_drafts WHERE documentId = :id")
    suspend fun draftTextChunk(id: String, start: Int): String?

    @Transaction
    suspend fun allDocuments(): List<DocumentEntity> = rawDocuments().map { fullDocument(it) }
    @Transaction
    suspend fun document(id: String): DocumentEntity? = rawDocument(id)?.let { fullDocument(it) }
    @Transaction
    suspend fun anyDocument(id: String): DocumentEntity? = rawAnyDocument(id)?.let { fullDocument(it) }
    @Transaction
    suspend fun documentsIncludingTrash(): List<DocumentEntity> = rawDocumentsIncludingTrash().map { fullDocument(it) }
    @Transaction
    suspend fun sentences(documentId: String): List<SentenceEntity> = rawSentences(documentId).map { fullSentence(it) }
    @Transaction
    suspend fun allSentences(documentId: String): List<SentenceEntity> = rawAllSentences(documentId).map { fullSentence(it) }
    @Transaction
    suspend fun activeSentence(id: String): SentenceEntity? = rawActiveSentence(id)?.let { fullSentence(it) }
    @Transaction
    suspend fun versions(documentId: String): List<DocumentVersionEntity> = rawVersions(documentId).map { row ->
        row.copy(text = if (row.text.isEmpty()) chunks { versionTextChunk(row.id, it) } else row.text,
            sentenceSnapshot = if (row.sentenceSnapshot == "") chunks { versionSnapshotChunk(row.id, it) } else row.sentenceSnapshot)
    }
    @Transaction
    suspend fun draft(documentId: String): EditorDraftEntity? = rawDraft(documentId)?.let { fullDraft(it) }
    @Transaction
    suspend fun allDrafts(): List<EditorDraftEntity> = rawAllDrafts().map { fullDraft(it) }

    private suspend fun fullDocument(row: DocumentEntity): DocumentEntity =
        if (row.text.isEmpty()) row.copy(text = chunks { documentTextChunk(row.id, it) }) else row
    private suspend fun fullSentence(row: SentenceEntity): SentenceEntity =
        if (row.text.isEmpty()) row.copy(text = chunks { sentenceTextChunk(row.id, it) }) else row
    private suspend fun fullDraft(row: EditorDraftEntity): EditorDraftEntity =
        if (row.text.isEmpty()) row.copy(text = chunks { draftTextChunk(row.documentId, it) }) else row

    /** SQLite substr uses Unicode code points, not UTF-16 offsets. Each row stays below CursorWindow limits. */
    private suspend fun chunks(read: suspend (Int) -> String?): String = buildString {
        var from = 1
        while (true) {
            val part = read(from).orEmpty()
            if (part.isEmpty()) break
            append(part)
            if (part.codePointCount(0, part.length) < 40000) break
            from += 40000
        }
    }

    @Transaction
    suspend fun replaceSentences(documentId: String, sentences: List<SentenceEntity>) {
        deleteSentences(documentId)
        putSentences(sentences)
    }
}
