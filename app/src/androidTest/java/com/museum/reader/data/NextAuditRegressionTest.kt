package com.museum.reader.data

import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.data.db.*
import com.museum.reader.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import com.museum.reader.ui.EditorController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Closing assertions for the four issues reported after v0.9.3. */
class NextAuditRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun db() = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
    private fun storedRows(database: ReaderDatabase) = listOf("documents", "sentences", "audio_assets",
        "document_versions", "bookmarks", "playlists", "playlist_items", "editor_drafts", "restore_receipts").associateWith { table ->
        database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) }
        }
    }

    @Test fun repeatedHistoryIdsAreRejectedBeforeAnyStoredContentChanges() = runBlocking<Unit> {
        val database = db(); val repo = DocumentRepository(context, database)
        try {
            val doc = repo.create("历史编号保护", "原正文。", DocumentSource.TXT)
            val manifest = JSONObject(repo.exportBackup(ReaderSettings()))
            val item = manifest.getJSONArray("documents").getJSONObject(0)
            fun version(text: String, time: Long) = JSONObject().put("id", "same-history-id")
                .put("title", "历史$time").put("text", text).put("createdAt", time)
            item.put("versions", JSONArray().put(version("旧甲。", 1)).put(version("旧乙。", 2)))
            assertTrue("Duplicate history IDs must be rejected", runCatching { repo.importBackup(manifest.toString()) }.isFailure)
            assertEquals(doc, repo.find(doc.id)); assertTrue(repo.versions(doc.id).isEmpty())
        } finally { database.close() }
    }

    @Test fun missingRouteReferencesNeedPreviewAcknowledgementAndRemainInResultWarnings() = runBlocking<Unit> {
        val database = db(); val repo = DocumentRepository(context, database)
        val file = File(context.cacheDir, UUID.randomUUID().toString() + ".json")
        try {
            val doc = repo.create("路线引用保护", "原正文。", DocumentSource.TXT)
            val payload = JSONObject(repo.exportBackup(ReaderSettings())).put("playlists", JSONArray().put(
                JSONObject().put("id", "partial-route").put("title", "缺站路线")
                    .put("documentIds", JSONArray().put(doc.id).put("missing-document"))))
            file.writeText(payload.toString())
            val manager = BackupManager(context, repo); val prepared = manager.prepare(Uri.fromFile(file))
            try {
                assertTrue("Missing station must be warned in preview", prepared.preview.warnings.any { "missing-document" in it })
                assertTrue(runCatching { manager.restore(prepared) }.isFailure)
                assertTrue(repo.playlists().isEmpty()); assertEquals(doc, repo.find(doc.id))
                val restored = manager.restore(prepared, acceptWarnings = true)
                assertTrue(restored.warnings.any { "missing-document" in it })
                assertEquals(listOf(doc.id), repo.playlists().single().documentIds)
            } finally { manager.discard(prepared) }
        } finally { file.delete(); database.close() }
    }

    @Test fun legacyOversizedDocumentHistoryAndDraftNamesCanBeReadWithoutChangingRawValues() = runBlocking<Unit> {
        val database = db(); val dao = database.readerDao(); val repo = DocumentRepository(context, database)
        try {
            val doc = repo.create("旧名称读取", "原正文。", DocumentSource.TXT)
            val original = "名".repeat(750_000)
            dao.updateTitle(doc.id, original, 1L)
            dao.putVersion(DocumentVersionEntity("legacy-long-version", doc.id, original, "旧正文。", 1L, null))
            dao.putDraft(EditorDraftEntity(doc.id, original, "草稿正文。", 1, 2, 1L))
            assertEquals(doc.id, repo.summaries().single().id)
            assertEquals(doc.text, repo.load(doc.id)!!.document.text)
            assertEquals("旧正文。", repo.versions(doc.id).single().text)
            assertEquals("草稿正文。", repo.draft(doc.id)!!.text)
            StorageMaintenance(context, repo, database).overview()
            for ((table, key) in listOf("documents" to "id", "document_versions" to "documentId", "editor_drafts" to "documentId")) {
                database.openHelper.readableDatabase.query("SELECT length(title) FROM $table WHERE $key = ?", arrayOf(doc.id)).use {
                    assertTrue(it.moveToFirst()); assertEquals(750_000, it.getInt(0))
                }
            }
        } finally { database.close() }
    }

    @Test fun duplicateHistoryIdsAcrossDocumentsAreRejectedButDistinctIdsWithEqualTextRemain() = runBlocking<Unit> {
        val database = db(); val repo = DocumentRepository(context, database)
        try {
            repo.create("甲", "甲正文。", DocumentSource.TXT); repo.create("乙", "乙正文。", DocumentSource.TXT)
            val root = JSONObject(repo.exportBackup(ReaderSettings())); val docs = root.getJSONArray("documents")
            for (i in 0..1) docs.getJSONObject(i).put("versions", JSONArray().put(JSONObject()
                .put("id", "shared-version").put("title", "历史$i").put("text", "相同旧正文。").put("createdAt", i + 1)))
            assertTrue(runCatching { repo.importBackup(root.toString()) }.isFailure)
            assertTrue(repo.all().all { repo.versions(it.id).isEmpty() })
            docs.getJSONObject(1).getJSONArray("versions").getJSONObject(0).put("id", "another-version")
            repo.importBackup(root.toString())
            assertEquals(2, repo.all().sumOf { repo.versions(it.id).size })
        } finally { database.close() }
    }

    @Test fun localDocumentOutsideBackupResolvesRouteAndDeletingItInvalidatesPreview() = runBlocking<Unit> {
        val sourceDb = db(); val localDb = db()
        val source = DocumentRepository(context, sourceDb); val local = DocumentRepository(context, localDb)
        val file = File(context.cacheDir, UUID.randomUUID().toString() + ".json")
        try {
            val a = source.create("备份甲", "甲。", DocumentSource.TXT)
            val b = local.create("仅在本机的乙", "乙。", DocumentSource.TXT)
            val root = JSONObject(source.exportBackup(ReaderSettings())).put("playlists", JSONArray().put(
                JSONObject().put("id", "local-reference-route").put("title", "联合引用")
                    .put("documentIds", JSONArray().put(a.id).put(b.id))))
            file.writeText(root.toString()); val manager = BackupManager(context, local)
            manager.restore(manager.prepare(Uri.fromFile(file)))
            assertEquals(listOf(a.id, b.id), local.playlists().single().documentIds)
            val prepared = manager.prepare(Uri.fromFile(file)); assertTrue(prepared.preview.warnings.isEmpty())
            localDb.readerDao().purgeDocument(b.id)
            try { assertTrue(runCatching { manager.restore(prepared, acceptWarnings = true) }.isFailure) }
            finally { manager.discard(prepared) }
            val refreshed = manager.prepare(Uri.fromFile(file))
            try { assertTrue(refreshed.preview.warnings.any { b.id in it }) } finally { manager.discard(refreshed) }
        } finally { file.delete(); sourceDb.close(); localDb.close() }
    }

    @Test fun routeAndMissingAudioWarningsAreBothRetainedAndLowLevelRestoreCannotBypassConfirmation() = runBlocking<Unit> {
        val database = db(); val repo = DocumentRepository(context, database)
        val file = File(context.cacheDir, UUID.randomUUID().toString() + ".json")
        try {
            val doc = repo.create("混合提示", "第一句。", DocumentSource.TXT)
            val root = JSONObject(repo.exportBackup(ReaderSettings())); val sentence = repo.load(doc.id)!!.content.sentences.single()
            root.put("playlists", JSONArray().put(JSONObject().put("id", "mixed-route").put("title", "混合路线")
                .put("documentIds", JSONArray().put(doc.id).put("missing-station"))))
            root.put("audio", JSONArray().put(JSONObject().put("id", "missing-audio").put("documentId", doc.id)
                .put("sentenceId", sentence.id).put("textHash", DocumentRepository.textHash(sentence.text))
                .put("kind", "RECORDING").put("entry", "audio/missing.wav")))
            file.writeText(root.toString()); val manager = BackupManager(context, repo); val prepared = manager.prepare(Uri.fromFile(file))
            try {
                assertEquals(2, prepared.preview.warnings.size)
                assertTrue(runCatching { repo.importBackup(root.toString()) }.isFailure)
                assertTrue(repo.playlists().isEmpty())
                assertEquals(prepared.preview.warnings.toSet(), manager.restore(prepared, acceptWarnings = true).warnings.toSet())
            } finally { manager.discard(prepared) }
        } finally { file.delete(); database.close() }
    }

    @Test fun legacyNamesSurviveEditorDraftAndBodySaveUntilExplicitRepairThenBackupWorks() = runBlocking<Unit> {
        val database = db(); val targetDb = db(); val dao = database.readerDao()
        val repo = DocumentRepository(context, database); val target = DocumentRepository(context, targetDb)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val wav = File(context.filesDir, "sentence-audio/name-" + UUID.randomUUID() + "/kept.wav")
        val zip = File(context.cacheDir, UUID.randomUUID().toString() + ".zip")
        try {
            val doc = repo.create("旧名保护", "原正文。", DocumentSource.TXT)
            val sentence = repo.load(doc.id)!!.content.sentences.single(); writeTestWav(wav)
            repo.putAudio(SentenceAudio("name-audio", doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", wav.path, DocumentRepository.textHash(sentence.text), 1000))
            val hash = AudioFiles.sha256(wav); val original = "旧".repeat(750_000)
            dao.updateTitle(doc.id, original, 1L)
            dao.putVersion(DocumentVersionEntity("old-name-history", doc.id, original, "预先历史。", 1L, null))
            dao.putDraft(EditorDraftEntity(doc.id, original, "原正文。草稿新增。", 1, 2, 1L))
            val controller = EditorController(repo, scope) {}
            controller.open(doc.id) {}
            withTimeout(15_000) { while (controller.state.value.pendingDraft == null) delay(30) }
            controller.chooseDraft(true)
            assertTrue(controller.state.value.title.length <= 1000); assertEquals(original, controller.state.value.retainedTitle)
            controller.change(controller.state.value.title, controller.state.value.text + "继续编辑。")
            controller.flush()
            withTimeout(15_000) { while (controller.state.value.persistedRevision != controller.state.value.revision) delay(30) }
            assertEquals(original, dao.draft(doc.id)!!.title)
            controller.save()
            withTimeout(15_000) { while (controller.state.value.saving || controller.state.value.dirty) delay(30) }
            assertEquals(original, dao.document(doc.id)!!.title)
            assertEquals(hash, AudioFiles.sha256(wav)); assertEquals(sentence.id, repo.load(doc.id)!!.content.sentences.first().id)
            assertTrue(runCatching { BackupManager(context, repo).export(Uri.fromFile(zip), ReaderSettings()) }.isFailure)
            repo.rename(doc.id, "已明确修正")
            val before = dao.versions(doc.id)
            before.forEachIndexed { i, v -> repo.renameVersion(doc.id, v.id, "修正历史$i") }
            dao.versions(doc.id).forEach { v ->
                val prior = before.single { it.id == v.id }
                assertEquals(prior.text, v.text); assertEquals(prior.createdAt, v.createdAt); assertEquals(prior.sentenceSnapshot, v.sentenceSnapshot)
            }
            assertFalse(dao.hasOversizedNames())
            BackupManager(context, repo).export(Uri.fromFile(zip), ReaderSettings())
            val manager = BackupManager(context, target); manager.restore(manager.prepare(Uri.fromFile(zip)))
            assertEquals(repo.find(doc.id)!!.text, target.find(doc.id)!!.text)
            assertEquals("已明确修正", target.find(doc.id)!!.title)
            assertEquals(hash, AudioFiles.sha256(File(target.audioAssets(doc.id).single().path)))
        } finally {
            scope.cancel()
            target.allAudioAssets().forEach { File(it.path).delete(); File(it.path).parentFile?.delete() }
            wav.delete(); wav.parentFile?.delete(); zip.delete(); database.close(); targetDb.close()
        }
    }

    @Test fun legacySupplementaryNamePreviewsStayWholeAndUnrelatedWritesPreserveFullName() = runBlocking<Unit> {
        val database = db(); val repo = DocumentRepository(context, database); val dao = database.readerDao()
        try {
            val doc = repo.create("补充字符旧名", "原句。", DocumentSource.TXT)
            val original = "名" + "😀".repeat(500); dao.updateTitle(doc.id, original, 1L)
            val shown = repo.summaries().single()
            assertTrue(shown.needsNameReview); assertTrue(shown.title.length <= 1000)
            assertEquals(shown.title, shown.title.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
            repo.setPinned(doc.id, true); repo.savePosition(doc.id, 0)
            repo.saveDocumentSettings(doc.id, 1.1f, null, null)
            repo.updateContent(doc.id, shown.title, "原句。新增句。")
            assertEquals(original, dao.document(doc.id)!!.title); assertTrue(dao.hasOversizedNames())
        } finally { database.close() }
    }

    @Test fun historicalNameWriteFailureKeepsVersionAndCurrentInputThenRetries() = runBlocking<Unit> {
        val database = db(); val dao = database.readerDao(); val repo = DocumentRepository(context, database)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val doc = repo.create("历史修名故障", "原正文。", DocumentSource.TXT)
            repo.updateContent(doc.id, doc.title, "当前正式正文。")
            val version = dao.versions(doc.id).single().copy(title = "旧".repeat(750_000))
            dao.putVersion(version)
            val formal = dao.document(doc.id)
            val controller = EditorController(repo, scope) {}
            controller.open(doc.id) {}
            withTimeout(15_000) { while (controller.state.value.document?.id != doc.id) delay(30) }
            withContext(Dispatchers.Main) { controller.change("仍在编辑的新标题", "尚未提交的新正文。") }
            database.withTransaction {
                database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_history_name BEFORE UPDATE OF title ON document_versions BEGIN SELECT RAISE(ABORT, 'synthetic-history-write'); END")
            }
            var completed = 0
            withContext(Dispatchers.Main) {
                controller.renameVersion(version.id, "已修正历史名") { completed++ }
                assertTrue(controller.state.value.renamingVersion)
                assertEquals("正在修正历史版本名称…", controller.state.value.draftStatus)
            }
            withTimeout(15_000) { while (controller.state.value.versionNameError == null) delay(30) }
            assertFalse(controller.state.value.saving); assertFalse(controller.state.value.renamingVersion)
            assertTrue(controller.state.value.versionNameError!!.contains("原版本仍保留"))
            assertEquals(0, completed); assertEquals(version, dao.versions(doc.id).single())
            assertEquals(formal, dao.document(doc.id))
            assertEquals("仍在编辑的新标题", controller.state.value.title)
            assertEquals("尚未提交的新正文。", controller.state.value.text)
            database.withTransaction { database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_history_name") }
            withContext(Dispatchers.Main) { controller.renameVersion(version.id, "已修正历史名") { completed++ } }
            withTimeout(15_000) { while (completed != 1) delay(30) }
            assertNull(controller.state.value.versionNameError); assertFalse(controller.state.value.saving)
            assertEquals(version.copy(title = "已修正历史名"), dao.versions(doc.id).single())
            assertEquals(formal, dao.document(doc.id)); assertTrue(controller.state.value.dirty)
            assertEquals("尚未提交的新正文。", controller.state.value.text)
        } finally { scope.cancel(); database.close() }
    }

    @Test fun duplicateHistoryRejectionPreservesAllRowsAndRecordingBytes() = runBlocking<Unit> {
        val database = db(); val repo = DocumentRepository(context, database)
        val wav = File(context.filesDir, "sentence-audio/duplicate-" + UUID.randomUUID() + "/kept.wav")
        try {
            val doc = repo.create("重复历史全行保护", "保留原句。", DocumentSource.TXT)
            val sentence = repo.load(doc.id)!!.content.sentences.single(); writeTestWav(wav)
            repo.putAudio(SentenceAudio("duplicate-kept-audio", doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", wav.path, DocumentRepository.textHash(sentence.text), 1000))
            repo.updateContent(doc.id, doc.title, "保留原句。新增句。")
            repo.saveDraft(EditorDraftEntity(doc.id, "保留草稿", "独立草稿。", 1, 2, 1L))
            val payload = JSONObject(repo.exportBackup(ReaderSettings()))
            val versions = payload.getJSONArray("documents").getJSONObject(0).getJSONArray("versions")
            versions.put(JSONObject(versions.getJSONObject(0).toString()).put("title", "同编号不同名称")
                .put("text", "另一份历史正文。").put("createdAt", 2L).put("sentenceSnapshot", JSONObject.NULL))
            val before = storedRows(database); val hash = AudioFiles.sha256(wav)
            val failure = runCatching { repo.importBackup(payload.toString()) }.exceptionOrNull()
            assertNotNull(failure); assertTrue(failure!!.message.orEmpty().contains("重复的历史版本编号"))
            assertEquals(before, storedRows(database)); assertEquals(hash, AudioFiles.sha256(wav))
        } finally { wav.delete(); wav.parentFile?.delete(); database.close() }
    }

    @Test fun missingRouteAcknowledgementPreservesPolicyMappingOrderAndRepeatImport() = runBlocking<Unit> {
        val sourceDb = db(); val source = DocumentRepository(context, sourceDb)
        val file = File(context.cacheDir, UUID.randomUUID().toString() + ".json")
        try {
            val doc = source.create("备份路线正文", "第一句。第二句。", DocumentSource.TXT)
            val payload = JSONObject(source.exportBackup(ReaderSettings())).put("playlists", JSONArray().put(
                JSONObject().put("id", "missing-policy-route").put("title", "三策略缺站路线")
                    .put("documentIds", JSONArray().put(doc.id).put("missing-policy-station"))))
            file.writeText(payload.toString())
            for (policy in BackupConflictPolicy.entries) {
                val localDb = db(); val local = DocumentRepository(context, localDb)
                try {
                    local.importBackup(source.exportBackup(ReaderSettings()))
                    local.rename(doc.id, "较新的本机名称")
                    val manager = BackupManager(context, local); val prepared = manager.prepare(Uri.fromFile(file))
                    try {
                        val before = storedRows(localDb)
                        assertTrue(runCatching { manager.restore(prepared, policy) }.isFailure)
                        assertEquals(before, storedRows(localDb))
                        val restored = manager.restore(prepared, policy, acceptWarnings = true)
                        assertTrue(restored.warnings.any { "missing-policy-station" in it })
                    } finally { manager.discard(prepared) }
                    val route = local.playlists().single(); assertEquals(1, route.documentIds.size)
                    if (policy == BackupConflictPolicy.COPY) {
                        assertNotEquals(doc.id, route.documentIds.single()); assertEquals(2, local.all().size)
                    } else assertEquals(listOf(doc.id), route.documentIds)
                    assertEquals(when (policy) {
                        BackupConflictPolicy.KEEP_LOCAL -> "较新的本机名称"
                        BackupConflictPolicy.COPY -> "备份路线正文（备份副本）"
                        BackupConflictPolicy.REPLACE -> "备份路线正文"
                    },
                        local.find(route.documentIds.single())!!.title)
                    val count = local.all().size
                    val repeated = manager.prepare(Uri.fromFile(file))
                    try { manager.restore(repeated, policy, acceptWarnings = true) } finally { manager.discard(repeated) }
                    assertEquals(count, local.all().size); assertEquals(route.documentIds, local.playlists().single().documentIds)
                } finally { localDb.close() }
            }
        } finally { file.delete(); sourceDb.close() }
    }
}
