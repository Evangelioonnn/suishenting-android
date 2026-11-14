package com.museum.reader.data

import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.data.db.EditorDraftEntity
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.AudioKind
import com.museum.reader.model.DocumentSource
import com.museum.reader.model.ReaderSettings
import com.museum.reader.model.SentenceAudio
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Closing regressions: assertions concern stored user data, never source text or diagnostic success. */
class BackupMetadataConflictTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private inner class Fixture {
        val sourceDb = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val localDb = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val source = DocumentRepository(context, sourceDb)
        val local = DocumentRepository(context, localDb)
        val manager = BackupManager(context, local)
        val folder = File(context.cacheDir, "metadata-" + UUID.randomUUID()).apply { mkdirs() }
        val file = File(folder, "source.museumreader")
        val pending = mutableListOf<PreparedBackup>()
        val recoveryPoints = mutableListOf<File>()
        suspend fun export() = BackupManager(context, source).export(Uri.fromFile(file), ReaderSettings())
        suspend fun prepare(): PreparedBackup = manager.prepare(Uri.fromFile(file)).also { pending += it }
        suspend fun restore(policy: BackupConflictPolicy = BackupConflictPolicy.KEEP_LOCAL): BackupImportResult =
            manager.restore(prepare(), policy).also { it.recoveryPoint?.let { path -> recoveryPoints += File(path) } }
        fun rows(): Map<String, List<List<String?>>> = listOf("documents", "sentences", "bookmarks",
            "document_versions", "editor_drafts", "playlists", "playlist_items", "audio_assets").associateWith { table ->
            localDb.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
                    .sortedBy { it.toString() }
            }
        }
        suspend fun close() {
            pending.forEach { if (it.directory.isDirectory) manager.discard(it) }
            local.allAudioAssets().map { File(it.path) }.forEach { it.delete(); it.parentFile?.delete() }
            recoveryPoints.forEach { it.delete() }
            folder.listFiles().orEmpty().forEach { it.delete() }; folder.delete()
            sourceDb.close(); localDb.close()
        }
    }
    private suspend fun Fixture.historicalDocument(): String {
        val doc = source.create("历史名称保护", "原正文。", DocumentSource.TXT)
        source.updateContent(doc.id, doc.title, "当前正文。")
        export(); restore()
        return doc.id
    }

    @Test fun repairedHistoricalNameIsAConflictAndKeepLocalPreservesAllRows() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val id = f.historicalDocument()
            val version = f.localDb.readerDao().versions(id).single()
            f.local.renameVersion(id, version.id, "用户修正历史名")
            val before = f.rows()
            val prepared = f.prepare()
            assertEquals(listOf("历史名称保护"), prepared.preview.conflictTitles)
            f.manager.restore(prepared, BackupConflictPolicy.KEEP_LOCAL)
            assertEquals(before, f.rows())
        } finally { f.close() }
    }

    @Test fun historicalNameChangedAfterPreviewRejectsTheStaleRestore() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val id = f.historicalDocument()
            val prepared = f.prepare()
            f.local.renameVersion(id, f.localDb.readerDao().versions(id).single().id, "预览后修正")
            val before = f.rows()
            assertTrue(runCatching { f.manager.restore(prepared, BackupConflictPolicy.REPLACE) }.isFailure)
            assertEquals(before, f.rows())
        } finally { f.close() }
    }

    @Test fun routeEditedAfterPreviewRejectsBeforeChangingAnyTable() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val a = f.source.create("路线甲", "甲。", DocumentSource.TXT)
            val b = f.source.create("路线乙", "乙。", DocumentSource.TXT)
            f.source.savePlaylist("metadata-route", "备份路线", listOf(a.id, b.id))
            f.export(); f.restore()
            val prepared = f.prepare()
            f.local.savePlaylist("metadata-route", "预览后的新路线", listOf(b.id))
            val before = f.rows()
            assertTrue(runCatching { f.manager.restore(prepared, BackupConflictPolicy.REPLACE) }.isFailure)
            assertEquals(before, f.rows())
        } finally { f.close() }
    }

    @Test fun removedBookmarkIsAConflictAndKeepLocalDoesNotResurrectIt() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val doc = f.source.create("重点保护", "原句。", DocumentSource.TXT)
            val sentence = f.source.load(doc.id)!!.content.sentences.single()
            f.source.toggleBookmark(doc.id, sentence.id)
            f.export(); f.restore()
            f.local.toggleBookmark(doc.id, sentence.id)
            val before = f.rows()
            val prepared = f.prepare()
            assertEquals(listOf(doc.title), prepared.preview.conflictTitles)
            f.manager.restore(prepared, BackupConflictPolicy.KEEP_LOCAL)
            assertEquals(before, f.rows()); assertTrue(f.local.bookmarks(doc.id).isEmpty())
        } finally { f.close() }
    }

    @Test fun distinctDraftCanBeCopiedWithoutOverwritingLocalDraftOrRepeatingCopies() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val doc = f.source.create("草稿另存", "相同正式正文。", DocumentSource.TXT)
            val backupDraft = EditorDraftEntity(doc.id, "备份草稿", "备份中未提交。", 1, 3, 10L)
            f.source.saveDraft(backupDraft); f.export(); f.restore()
            val localDraft = backupDraft.copy(title = "本机新草稿", text = "本机较新输入。", updatedAt = 20L)
            f.local.saveDraft(localDraft)
            assertEquals(listOf(doc.title), f.prepare().preview.conflictTitles)
            f.restore(BackupConflictPolicy.COPY)
            val copy = f.local.all().single { it.id != doc.id }
            assertEquals(localDraft, f.local.draft(doc.id))
            assertEquals(backupDraft.copy(documentId = copy.id), f.local.draft(copy.id))
            f.restore(BackupConflictPolicy.COPY); assertEquals(2, f.local.all().size)
            assertEquals(localDraft, f.local.draft(doc.id))
        } finally { f.close() }
    }

    @Test fun replaceSameFormalTextRestoresChosenDraftAndPreservesRecoveryPoint() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val doc = f.source.create("明确覆盖草稿", "正式正文。", DocumentSource.TXT)
            val old = EditorDraftEntity(doc.id, "备份草稿", "备份输入。", 0, 2, 10L)
            f.source.saveDraft(old); f.export(); f.restore()
            val newer = old.copy(title = "本机草稿", text = "本机较新输入。", updatedAt = 20L)
            f.local.saveDraft(newer)
            val result = f.restore(BackupConflictPolicy.REPLACE)
            assertNotNull(result.recoveryPoint)
            assertEquals(old, f.local.draft(doc.id))
            val point = f.manager.prepare(Uri.fromFile(File(result.recoveryPoint!!))).also { f.pending += it }
            assertEquals(newer, point.graph.documents.single().draft)
        } finally { f.close() }
    }

    @Test fun explicitReplacementHonorsAbsenceOfDraftAndBookmarks() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val doc = f.source.create("精确覆盖关联", "正式正文。", DocumentSource.TXT)
            f.export(); f.restore()
            val sentence = f.local.load(doc.id)!!.content.sentences.single()
            f.local.toggleBookmark(doc.id, sentence.id)
            f.local.saveDraft(EditorDraftEntity(doc.id, "本机草稿", "本机输入。", 0, 2, 30L))
            val result = f.restore(BackupConflictPolicy.REPLACE)
            assertNotNull(result.recoveryPoint)
            assertNull(f.local.draft(doc.id)); assertTrue(f.local.bookmarks(doc.id).isEmpty())
            assertEquals(sentence.id, f.local.load(doc.id)!!.content.sentences.single().id)
        } finally { f.close() }
    }

    @Test fun reexportingAndRestoringTheSameHistoryDoesNotCreateDuplicates() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val id = f.historicalDocument()
            val before = f.localDb.readerDao().versions(id)
            repeat(3) {
                val file = File(f.folder, "reexport-$it.museumreader")
                f.manager.export(Uri.fromFile(file), ReaderSettings())
                val prepared = f.manager.prepare(Uri.fromFile(file)).also { f.pending += it }
                assertTrue(prepared.preview.conflictTitles.isEmpty())
                f.manager.restore(prepared)
                assertEquals(before, f.localDb.readerDao().versions(id))
            }
        } finally { f.close() }
    }

    @Test fun routeOnlyReplacementPreservesALocalRecoveryPoint() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val a = f.source.create("路线覆盖甲", "甲。", DocumentSource.TXT)
            val b = f.source.create("路线覆盖乙", "乙。", DocumentSource.TXT)
            f.source.savePlaylist("metadata-route-only", "备份路线", listOf(a.id, b.id))
            f.export(); f.restore()
            f.local.savePlaylist("metadata-route-only", "本机路线", listOf(b.id, a.id))
            f.local.savePlaylistPosition("metadata-route-only", b.id)
            val before = f.local.playlists().single()
            val result = f.restore(BackupConflictPolicy.REPLACE)
            assertNotNull(result.recoveryPoint)
            assertEquals(listOf(a.id, b.id), f.local.playlists().single().documentIds)
            val point = f.manager.prepare(Uri.fromFile(File(result.recoveryPoint!!))).also { f.pending += it }
            val route = point.graph.root.getJSONArray("playlists").getJSONObject(0)
            assertEquals(before.title, route.getString("title"))
            assertEquals(b.id, route.getString("lastDocumentId"))
            assertEquals(listOf(b.id, a.id), (0 until route.getJSONArray("documentIds").length()).map { route.getJSONArray("documentIds").getString(it) })
        } finally { f.close() }
    }

    @Test fun bookmarkAndDraftChangesAfterPreviewCannotBeSilentlyCommitted() = runBlocking<Unit> {
        for (draft in listOf(false, true)) {
            val f = Fixture()
            try {
                val doc = f.source.create("预览关联保护", "正文。", DocumentSource.TXT)
                f.export(); f.restore()
                val prepared = f.prepare()
                if (draft) f.local.saveDraft(EditorDraftEntity(doc.id, "后来草稿", "预览后输入。", 0, 2, 20L))
                else f.local.toggleBookmark(doc.id, f.local.load(doc.id)!!.content.sentences.single().id)
                val before = f.rows()
                assertTrue(runCatching { f.manager.restore(prepared, BackupConflictPolicy.REPLACE) }.isFailure)
                assertEquals(before, f.rows())
            } finally { f.close() }
        }
    }

    @Test fun routeOnlyKeepAndCopyPreserveLocalGraphAndReuseTheIndependentRoute() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val a = f.source.create("路线保留甲", "甲。", DocumentSource.TXT)
            val b = f.source.create("路线保留乙", "乙。", DocumentSource.TXT)
            f.source.savePlaylist("metadata-route-copy", "备份路线", listOf(a.id, b.id))
            f.source.savePlaylistPosition("metadata-route-copy", a.id)
            f.export(); f.restore()
            f.local.savePlaylist("metadata-route-copy", "本机路线", listOf(b.id, a.id))
            f.local.savePlaylistPosition("metadata-route-copy", b.id)
            val before = f.rows(); val localRoute = f.local.playlists().single()
            val preview = f.prepare().preview
            assertTrue(preview.conflictTitles.isEmpty()); assertEquals(listOf("备份路线"), preview.routeConflictTitles)
            f.restore(); assertEquals(before, f.rows())
            f.restore(BackupConflictPolicy.COPY)
            assertEquals(2, f.local.all().size); assertEquals(2, f.local.playlists().size)
            assertEquals(localRoute, f.local.playlists().first { it.id == localRoute.id })
            val copied = f.local.playlists().single { it.id != localRoute.id }
            assertEquals(listOf(a.id, b.id), copied.documentIds); assertEquals(a.id, copied.lastDocumentId)
            val copiedRows = f.rows()
            f.restore(BackupConflictPolicy.COPY); assertEquals(copiedRows, f.rows())
        } finally { f.close() }
    }

    @Test fun routeContinuePositionAloneIsProtectedAndCanBeCopied() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val a = f.source.create("继续甲", "甲。", DocumentSource.TXT)
            val b = f.source.create("继续乙", "乙。", DocumentSource.TXT)
            f.source.savePlaylist("metadata-route-position", "同名路线", listOf(a.id, b.id))
            f.source.savePlaylistPosition("metadata-route-position", a.id)
            f.export(); f.restore(); f.local.savePlaylistPosition("metadata-route-position", b.id)
            assertEquals(listOf("同名路线"), f.prepare().preview.routeConflictTitles)
            f.restore(BackupConflictPolicy.COPY)
            assertEquals(b.id, f.local.playlists().single { it.id == "metadata-route-position" }.lastDocumentId)
            assertEquals(a.id, f.local.playlists().single { it.id != "metadata-route-position" }.lastDocumentId)
            f.restore(BackupConflictPolicy.COPY); assertEquals(2, f.local.playlists().size)
        } finally { f.close() }
    }

    @Test fun historyNameConflictCanBeCopiedWithoutChangingTheRepairedOriginal() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val id = f.historicalDocument()
            f.local.renameVersion(id, f.localDb.readerDao().versions(id).single().id, "本机修正历史")
            val before = f.localDb.readerDao().versions(id)
            f.restore(BackupConflictPolicy.COPY)
            val copy = f.local.all().single { it.id != id }
            assertEquals(before, f.localDb.readerDao().versions(id))
            assertEquals("历史名称保护", f.localDb.readerDao().versions(copy.id).single().title)
            assertEquals("原正文。", f.localDb.readerDao().versions(copy.id).single().text)
            f.restore(BackupConflictPolicy.COPY); assertEquals(2, f.local.all().size)
            assertEquals(before, f.localDb.readerDao().versions(id))
        } finally { f.close() }
    }

    @Test fun explicitHistoryNameReplacementKeepsRecoveryPointAndIsIdempotent() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val id = f.historicalDocument()
            f.local.renameVersion(id, f.localDb.readerDao().versions(id).single().id, "本机修正历史")
            val result = f.restore(BackupConflictPolicy.REPLACE)
            assertNotNull(result.recoveryPoint)
            assertEquals("历史名称保护", f.localDb.readerDao().versions(id).single().title)
            val point = f.manager.prepare(Uri.fromFile(File(result.recoveryPoint!!))).also { f.pending += it }
            assertEquals("本机修正历史", point.graph.documents.single().versions.single().title)
            val before = f.rows()
            assertFalse(f.prepare().preview.hasConflicts)
            f.restore(BackupConflictPolicy.REPLACE); assertEquals(before, f.rows())
        } finally { f.close() }
    }

    @Test fun playbackPositionMayAdvanceButRoutePositionInvalidatesAReplacementPreview() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val a = f.source.create("阅读位置甲", "第一句。第二句。", DocumentSource.TXT)
            val b = f.source.create("阅读位置乙", "乙。", DocumentSource.TXT)
            f.source.savePlaylist("metadata-live-position", "路线", listOf(a.id, b.id))
            f.source.savePlaylistPosition("metadata-live-position", a.id)
            f.export(); f.restore()
            val positionPreview = f.prepare()
            f.local.savePosition(a.id, 1)
            f.manager.restore(positionPreview)
            assertEquals(1, f.local.find(a.id)!!.lastSentence)
            val routePreview = f.prepare()
            f.local.savePlaylistPosition("metadata-live-position", b.id)
            val before = f.rows()
            assertTrue(runCatching { f.manager.restore(routePreview, BackupConflictPolicy.REPLACE) }.isFailure)
            assertEquals(before, f.rows())
        } finally { f.close() }
    }

    @Test fun mappedHistoryIdentifierCannotReplaceAnUnrelatedDocumentsHistory() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val id = f.historicalDocument()
            val sourceVersion = f.sourceDb.readerDao().versions(id).single()
            val other = f.local.create("无关文稿", "无关正文。", DocumentSource.TXT)
            val collision = stableId("version:$id:${sourceVersion.id}")
            val original = f.localDb.readerDao().versions(id).single()
            // Construct a valid unrelated legacy row occupying the deterministic destination ID.
            f.localDb.openHelper.writableDatabase.execSQL("DELETE FROM document_versions WHERE documentId = ?", arrayOf(id))
            val unrelated = original.copy(id = collision, documentId = other.id, title = "必须保留的其他历史", text = "其他历史正文。", sentenceSnapshot = null)
            f.localDb.readerDao().putVersion(unrelated)
            f.restore(BackupConflictPolicy.REPLACE)
            assertEquals(unrelated, f.localDb.readerDao().versions(other.id).single())
            assertEquals(sourceVersion.text, f.localDb.readerDao().versions(id).single().text)
            assertNotEquals(collision, f.localDb.readerDao().versions(id).single().id)
        } finally { f.close() }
    }
}
