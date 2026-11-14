package com.museum.reader.data

import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.data.db.*
import com.museum.reader.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Regressions for the six findings from the v0.9.2 read-only audit. */
class AuditBackupRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun db() = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()

    @Test fun oldBackupPreviewsAudioConflictAndDefaultRestoreKeepsNewRecording() = runBlocking<Unit> {
        val sourceDb = db(); val localDb = db()
        val source = DocumentRepository(context, sourceDb); val local = DocumentRepository(context, localDb)
        val zip = File(context.cacheDir, UUID.randomUUID().toString())
        val audio = File(context.filesDir, "sentence-audio/audit-" + UUID.randomUUID() + "/old.wav")
        val newer = File(audio.parentFile, "new.wav")
        var recoveryPoint: File? = null
        try {
            val doc = source.create("录音冲突", "正文没有改动。", DocumentSource.TXT)
            val sentence = source.load(doc.id)!!.content.sentences.single()
            writeTestWav(audio); writeTestWav(newer)
            newer.writeBytes(newer.readBytes().also { it[100] = (it[100].toInt() xor 1).toByte() })
            source.putAudio(SentenceAudio("audit-old", doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", audio.path, DocumentRepository.textHash(sentence.text), 1000))
            BackupManager(context, source).export(Uri.fromFile(zip), ReaderSettings())
            val manager = BackupManager(context, local)
            manager.restore(manager.prepare(Uri.fromFile(zip)))
            local.putAudio(SentenceAudio("audit-new", doc.id, sentence.id, AudioKind.RECORDING,
                "local-v2", "new-self", newer.path, DocumentRepository.textHash(sentence.text), 1000))
            val before = local.allAudioAssets()
            val prepared = manager.prepare(Uri.fromFile(zip))
            assertEquals(listOf(doc.title), prepared.preview.conflictTitles)
            manager.restore(prepared)
            assertEquals(before, local.allAudioAssets())
            assertEquals("audit-new", local.latestAudio(sentence.id, AudioKind.RECORDING)!!.id)
            assertEquals(AudioFiles.sha256(newer), AudioFiles.sha256(File(local.latestAudio(sentence.id, AudioKind.RECORDING)!!.path)))
            val copy = manager.restore(manager.prepare(Uri.fromFile(zip)), BackupConflictPolicy.COPY)
            assertEquals(1, copy.documentCount)
            val copied = local.all().single { it.id != doc.id }
            assertEquals(doc.text, copied.text)
            assertEquals(AudioFiles.sha256(audio), AudioFiles.sha256(File(local.audioAssets(copied.id).single().path)))
            assertEquals("audit-new", local.latestAudio(sentence.id, AudioKind.RECORDING)!!.id)
            val replacement = manager.restore(manager.prepare(Uri.fromFile(zip)), BackupConflictPolicy.REPLACE)
            recoveryPoint = File(checkNotNull(replacement.recoveryPoint))
            assertEquals("audit-old", local.latestAudio(sentence.id, AudioKind.RECORDING)!!.id)
            assertEquals(1, local.audioAssets(doc.id).count { it.kind == AudioKind.RECORDING && it.archivedAt == null })
            val preserved = manager.prepare(Uri.fromFile(recoveryPoint))
            try {
                assertTrue(preserved.audio.any { it.id == "audit-new" && it.archivedAt == null &&
                    AudioFiles.sha256(File(it.path)) == AudioFiles.sha256(newer) })
            } finally { manager.discard(preserved) }
        } finally {
            local.allAudioAssets().map { File(it.path) }.filter { it != newer }.forEach { it.delete(); it.parentFile?.delete() }
            recoveryPoint?.delete(); audio.delete(); newer.delete(); audio.parentFile?.delete(); zip.delete(); sourceDb.close(); localDb.close()
        }
    }

    @Test fun repeatedCopyPreservesEditedCopyAndReusesTheNewUnmodifiedCopy() = runBlocking<Unit> {
        val sourceDb = db(); val localDb = db()
        val source = DocumentRepository(context, sourceDb); val local = DocumentRepository(context, localDb)
        val file = File(context.cacheDir, UUID.randomUUID().toString() + ".json")
        try {
            val doc = source.create("副本保护", "备份正文。", DocumentSource.TXT)
            file.writeText(source.exportBackup(ReaderSettings()))
            local.importBackup(file.readText()); local.updateContent(doc.id, doc.title, "本机新正文。")
            val manager = BackupManager(context, local)
            manager.restore(manager.prepare(Uri.fromFile(file)), BackupConflictPolicy.COPY)
            val first = local.all().single { it.id != doc.id }
            local.updateContent(first.id, "已编辑副本", first.text + "后来编辑。")
            manager.restore(manager.prepare(Uri.fromFile(file)), BackupConflictPolicy.COPY)
            assertEquals("备份正文。后来编辑。", local.find(first.id)!!.text)
            assertEquals("已编辑副本", local.find(first.id)!!.title)
            assertEquals(3, local.all().size)
            manager.restore(manager.prepare(Uri.fromFile(file)), BackupConflictPolicy.COPY)
            assertEquals(3, local.all().size)
        } finally { file.delete(); sourceDb.close(); localDb.close() }
    }

    @Test fun malformedHistoryEntriesAreRejectedBeforeAnyCommit() = runBlocking<Unit> {
        val database = db(); val repo = DocumentRepository(context, database)
        try {
            val doc = repo.create("快照校验", "原有正文。", DocumentSource.TXT)
            val manifest = JSONObject(repo.exportBackup(ReaderSettings()))
            val entry = manifest.getJSONArray("documents").getJSONObject(0)
            for (snapshot in listOf("[{}]", "[1]", "[{\"id\":\"missing\",\"text\":\"原有正文。\"}]")) {
                entry.put("versions", JSONArray().put(JSONObject().put("id", "bad-version").put("title", doc.title)
                    .put("text", doc.text).put("createdAt", 1).put("sentenceSnapshot", snapshot)))
                assertTrue("Must reject malformed snapshot $snapshot", runCatching { repo.parseBackup(manifest.toString()) }.isFailure)
                assertEquals(doc, repo.find(doc.id)); assertTrue(repo.versions(doc.id).isEmpty())
            }
        } finally { database.close() }
    }

    @Test fun existingBrokenHistoryDoesNotBlockOverviewOrUnprotectOldRecording() = runBlocking<Unit> {
        val database = db(); val repo = DocumentRepository(context, database)
        val wav = File(context.filesDir, "sentence-audio/audit-" + UUID.randomUUID() + "/kept.wav")
        try {
            val doc = repo.create("旧异常历史", "仍可阅读。", DocumentSource.TXT)
            val sentence = repo.load(doc.id)!!.content.sentences.single(); writeTestWav(wav)
            repo.putAudio(SentenceAudio("old-protected", doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", wav.path, DocumentRepository.textHash(sentence.text), 1000, 1L))
            database.readerDao().putVersion(DocumentVersionEntity("broken-version", doc.id, doc.title, doc.text, 1L, "[{}]"))
            val overview = StorageMaintenance(context, repo, database).overview()
            assertTrue(overview.items.single().protected)
            assertEquals("[{}]", database.readerDao().versions(doc.id).single().sentenceSnapshot)
            assertTrue(wav.isFile)
        } finally { wav.delete(); wav.parentFile?.delete(); database.close() }
    }

    @Test fun oversizedRouteMetadataIsRejectedBeforeCommit() = runBlocking<Unit> {
        val database = db(); val repo = DocumentRepository(context, database)
        try {
            val doc = repo.create("不受影响的文稿", "原有正文。", DocumentSource.TXT)
            val manifest = JSONObject(repo.exportBackup(ReaderSettings())).put("playlists", JSONArray().put(
                JSONObject().put("id", "oversized-route").put("title", "线".repeat(750_000)).put("documentIds", JSONArray())))
            assertTrue(runCatching { repo.parseBackup(manifest.toString()) }.isFailure)
            assertEquals(doc, repo.find(doc.id)); assertTrue(repo.playlists().isEmpty())
        } finally { database.close() }
    }

    @Test fun existingOversizedRouteCanBeReadWithoutChangingItsStoredName() = runBlocking<Unit> {
        val database = db(); val repo = DocumentRepository(context, database)
        try {
            val doc = repo.create("旧异常库中的文稿", "原有正文。", DocumentSource.TXT)
            database.readerDao().putPlaylist(PlaylistEntity("old-big-route", "线".repeat(750_000), 1L, 1L))
            assertEquals(doc.id, repo.summaries().single().id)
            assertTrue(repo.playlists().single().title.length <= 1000)
            assertTrue(repo.playlists().single().needsNameReview)
            repo.savePlaylist("old-big-route", repo.playlists().single().title, listOf(doc.id))
            database.openHelper.readableDatabase.query("SELECT length(title) FROM playlists WHERE id='old-big-route'").use {
                assertTrue(it.moveToFirst()); assertEquals(750_000, it.getInt(0))
            }
            val destination = File(context.cacheDir, UUID.randomUUID().toString())
            try { assertTrue(runCatching { BackupManager(context, repo).export(Uri.fromFile(destination), ReaderSettings()) }.isFailure) }
            finally { destination.delete() }
        } finally { database.close() }
    }

    @Test fun copyAlsoProtectsNewDraftAndNewRecordingWhenTextIsUnchanged() = runBlocking<Unit> {
        val sourceDb = db(); val localDb = db()
        val source = DocumentRepository(context, sourceDb); val local = DocumentRepository(context, localDb)
        val zip = File(context.cacheDir, UUID.randomUUID().toString())
        val wav = File(context.filesDir, "sentence-audio/audit-" + UUID.randomUUID() + "/copy.wav")
        try {
            val doc = source.create("副本关联保护", "备份正文。", DocumentSource.TXT)
            val sentence = source.load(doc.id)!!.content.sentences.single(); writeTestWav(wav)
            source.putAudio(SentenceAudio("copy-original", doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", wav.path, DocumentRepository.textHash(sentence.text), 1000))
            val route = source.savePlaylist(null, "关联路线", listOf(doc.id))
            BackupManager(context, source).export(Uri.fromFile(zip), ReaderSettings())
            val manager = BackupManager(context, local)
            manager.restore(manager.prepare(Uri.fromFile(zip)))
            local.updateContent(doc.id, doc.title, "本机新正文。")
            manager.restore(manager.prepare(Uri.fromFile(zip)), BackupConflictPolicy.COPY)
            val first = local.all().single { it.id != doc.id }
            val firstSentence = local.load(first.id)!!.content.sentences.single()
            local.saveDraft(EditorDraftEntity(first.id, "后来草稿", "尚未提交的新内容。", 1, 2, 10L))
            local.putAudio(SentenceAudio("copy-new-recording", first.id, firstSentence.id, AudioKind.RECORDING,
                "local", "self", wav.path, DocumentRepository.textHash(firstSentence.text), 1000))
            val before = local.audioAssets(first.id)
            val previousRoutes = local.playlists()
            manager.restore(manager.prepare(Uri.fromFile(zip)), BackupConflictPolicy.COPY)
            assertEquals(before, local.audioAssets(first.id))
            assertEquals("尚未提交的新内容。", local.draft(first.id)!!.text)
            assertEquals(3, local.all().size)
            assertTrue(previousRoutes.all { old -> local.playlists().any { it == old } })
            val second = local.all().single { it.id != doc.id && it.id != first.id }
            assertTrue(local.playlists().any { it.id != route.id && it.documentIds == listOf(second.id) })
            manager.restore(manager.prepare(Uri.fromFile(zip)), BackupConflictPolicy.COPY)
            assertEquals(3, local.all().size)
        } finally {
            local.allAudioAssets().map { File(it.path) }.filter { it != wav }.forEach { it.delete(); it.parentFile?.delete() }
            wav.delete(); wav.parentFile?.delete(); zip.delete(); sourceDb.close(); localDb.close()
        }
    }

    @Test fun previewCannotCommitAfterTheActiveRecordingChanges() = runBlocking<Unit> {
        val sourceDb = db(); val localDb = db()
        val source = DocumentRepository(context, sourceDb); val local = DocumentRepository(context, localDb)
        val zip = File(context.cacheDir, UUID.randomUUID().toString())
        val wav = File(context.filesDir, "sentence-audio/audit-" + UUID.randomUUID() + "/fingerprint.wav")
        try {
            val doc = source.create("预览时效", "正文相同。", DocumentSource.TXT)
            val sentence = source.load(doc.id)!!.content.sentences.single(); writeTestWav(wav)
            source.putAudio(SentenceAudio("fingerprint-old", doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", wav.path, DocumentRepository.textHash(sentence.text), 1000))
            BackupManager(context, source).export(Uri.fromFile(zip), ReaderSettings())
            val manager = BackupManager(context, local)
            manager.restore(manager.prepare(Uri.fromFile(zip)))
            val preview = manager.prepare(Uri.fromFile(zip))
            local.putAudio(SentenceAudio("fingerprint-new", doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", wav.path, DocumentRepository.textHash(sentence.text), 1000))
            assertTrue(runCatching { manager.restore(preview, BackupConflictPolicy.REPLACE) }.isFailure)
            assertEquals("fingerprint-new", local.latestAudio(sentence.id, AudioKind.RECORDING)!!.id)
            manager.discard(preview)
        } finally {
            local.allAudioAssets().map { File(it.path) }.filter { it != wav }.forEach { it.delete(); it.parentFile?.delete() }
            wav.delete(); wav.parentFile?.delete(); zip.delete(); sourceDb.close(); localDb.close()
        }
    }

    @Test fun namesAtTheLimitRoundTripAndExcessIsRejectedWithoutChangingData() = runBlocking<Unit> {
        val database = db(); val repo = DocumentRepository(context, database)
        try {
            val doc = repo.create("名".repeat(1000), "边界正文。", DocumentSource.TXT)
            val route = repo.savePlaylist(null, "线".repeat(1000), listOf(doc.id))
            val payload = repo.exportBackup(ReaderSettings())
            assertNotNull(repo.parseBackup(payload))
            assertTrue(runCatching { repo.rename(doc.id, "名".repeat(1001)) }.isFailure)
            assertTrue(runCatching { repo.savePlaylist(route.id, "线".repeat(1001), listOf(doc.id)) }.isFailure)
            assertEquals(doc, repo.find(doc.id)); assertEquals(1000, repo.playlists().single().title.length)
        } finally { database.close() }
    }

    @Test fun supplementaryCharactersAtCopyNameLimitStayIntactAndRepeatedCopyIsIdempotent() = runBlocking<Unit> {
        val sourceDb = db(); val localDb = db()
        val file = File(context.cacheDir, "copy-name-" + UUID.randomUUID() + ".json")
        try {
            val source = DocumentRepository(context, sourceDb); val local = DocumentRepository(context, localDb)
            val title = "名" + "😀".repeat(499)
            val doc = source.create(title, "备份正文。", DocumentSource.TXT)
            file.writeText(source.exportBackup(ReaderSettings()))
            local.importBackup(file.readText()); local.updateContent(doc.id, title, "本机新正文。")
            val manager = BackupManager(context, local)
            manager.restore(manager.prepare(Uri.fromFile(file)), BackupConflictPolicy.COPY)
            manager.restore(manager.prepare(Uri.fromFile(file)), BackupConflictPolicy.COPY)
            assertEquals(2, local.all().size)
            val copied = local.all().single { it.id != doc.id }
            assertTrue(copied.title.length <= 1000)
            assertTrue(copied.title.endsWith("（备份副本）"))
            assertTrue(title.startsWith(copied.title.removeSuffix("（备份副本）")))
            assertEquals(copied.title, copied.title.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
            assertEquals(title, local.find(doc.id)!!.title)
            assertEquals("本机新正文。", local.find(doc.id)!!.text)
        } finally { file.delete(); sourceDb.close(); localDb.close() }
    }
}
