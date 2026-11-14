package com.museum.reader.data

import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.cloud.CloudSpeechPreferencesRepository
import com.museum.reader.data.db.BookmarkEntity
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Malformed input and collisions must not silently damage unrelated user data. */
class BackupBoundaryHardeningTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private inner class Fixture {
        val sourceDb = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val localDb = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val source = DocumentRepository(context, sourceDb)
        val local = DocumentRepository(context, localDb)
        val manager = BackupManager(context, local)
        val directory = File(context.filesDir, "sentence-audio/boundary-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(directory, "source.museumreader")
        val pending = mutableListOf<PreparedBackup>()
        suspend fun export() = BackupManager(context, source).export(Uri.fromFile(file), ReaderSettings())
        suspend fun prepare() = manager.prepare(Uri.fromFile(file)).also { pending += it }
        suspend fun close() {
            pending.forEach { if (it.directory.exists()) manager.discard(it) }
            (source.allAudioAssets() + local.allAudioAssets()).map { File(it.path) }.distinct().forEach { it.delete(); it.parentFile?.delete() }
            directory.listFiles().orEmpty().forEach { it.delete() }; directory.delete()
            sourceDb.close(); localDb.close()
        }
    }

    @Test fun incompleteHistorySnapshotIsRejectedWithoutChangingAnyLocalDocument() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val doc = f.source.create("完整历史", "第一句。第二句。", DocumentSource.TXT)
            f.source.updateContent(doc.id, doc.title, "当前正文。")
            val root = JSONObject(f.source.exportBackup(ReaderSettings()))
            val version = root.getJSONArray("documents").getJSONObject(0).getJSONArray("versions").getJSONObject(0)
            val snapshot = JSONArray(version.getString("sentenceSnapshot"))
            assertEquals(2, snapshot.length())
            version.put("sentenceSnapshot", JSONArray().put(snapshot.getJSONObject(0)).toString())
            assertTrue("Partial historical identity must be rejected", runCatching { f.local.parseBackup(root.toString()) }.isFailure)
            assertTrue(f.local.all().isEmpty())
        } finally { f.close() }
    }

    @Test fun misorderedHistorySnapshotIsRejectedEvenWhenEveryReferenceExists() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val doc = f.source.create("有序历史", "第一句。第二句。", DocumentSource.TXT)
            f.source.updateContent(doc.id, doc.title, "当前正文。")
            val root = JSONObject(f.source.exportBackup(ReaderSettings()))
            val version = root.getJSONArray("documents").getJSONObject(0).getJSONArray("versions").getJSONObject(0)
            val snapshot = JSONArray(version.getString("sentenceSnapshot"))
            version.put("sentenceSnapshot", JSONArray().put(snapshot.getJSONObject(1)).put(snapshot.getJSONObject(0)).toString())
            assertTrue("Misordered historical identity must be rejected", runCatching { f.local.parseBackup(root.toString()) }.isFailure)
        } finally { f.close() }
    }

    @Test fun duplicateRouteStationsAreRejectedRatherThanSilentlyCompressed() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val doc = f.source.create("路线输入", "正文。", DocumentSource.TXT)
            f.source.savePlaylist("boundary-route", "路线", listOf(doc.id))
            val root = JSONObject(f.source.exportBackup(ReaderSettings()))
            root.getJSONArray("playlists").getJSONObject(0).put("documentIds", JSONArray(listOf(doc.id, doc.id)))
            assertTrue("Duplicate stations must not be lost silently", runCatching { f.local.parseBackup(root.toString()) }.isFailure)
        } finally { f.close() }
    }

    @Test fun deterministicBookmarkCollisionCannotReplaceAnUnrelatedBookmark() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val doc = f.source.create("恢复重点", "恢复的句子。", DocumentSource.TXT)
            val sentence = f.source.load(doc.id)!!.content.sentences.single()
            f.source.toggleBookmark(doc.id, sentence.id)
            val other = f.local.create("本机无关文稿", "必须保留的重点。", DocumentSource.TXT)
            val otherSentence = f.local.load(other.id)!!.content.sentences.single()
            val original = BookmarkEntity(stableId("bookmark:${doc.id}:${sentence.id}"), other.id, otherSentence.id, 10)
            f.localDb.readerDao().putBookmark(original)
            f.export(); f.manager.restore(f.prepare())
            assertEquals(listOf(original), f.localDb.readerDao().bookmarks(other.id))
            assertEquals(sentence.id, f.local.bookmarks(doc.id).single().sentenceId)
            val restored = f.localDb.readerDao().bookmarks(doc.id)
            f.manager.restore(f.prepare())
            assertEquals(restored, f.localDb.readerDao().bookmarks(doc.id))
            assertEquals(listOf(original), f.localDb.readerDao().bookmarks(other.id))
        } finally { f.close() }
    }

    @Test fun deterministicAudioCollisionCannotReplaceEitherUnrelatedAsset() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val doc = f.source.create("恢复录音", "恢复的句子。", DocumentSource.TXT)
            val sentence = f.source.load(doc.id)!!.content.sentences.single()
            val recording = File(f.directory, "source.wav").also(::writeTestWav)
            val sourceId = UUID.randomUUID().toString()
            f.source.putAudio(SentenceAudio(sourceId, doc.id, sentence.id, AudioKind.RECORDING, "local", "self", recording.path,
                DocumentRepository.textHash(sentence.text), 1000))
            f.export()
            val graph = f.prepare().graph
            val other = f.local.create("无关录音文稿", "必须保留的录音。", DocumentSource.TXT)
            val otherSentence = f.local.load(other.id)!!.content.sentences.single()
            val hash = DocumentRepository.textHash(otherSentence.text)
            for ((index, id) in listOf(sourceId, stableId("audio:${graph.fingerprint}:${doc.id}:$sourceId")).withIndex()) {
                val old = File(f.directory, "local-$index.wav").also(::writeTestWav)
                f.local.putAudio(SentenceAudio(id, other.id, otherSentence.id, AudioKind.RECORDING, "local", "self", old.path, hash, 1000, archivedAt = 1L))
            }
            val before = f.local.allAudioAssets()
            f.manager.restore(f.prepare())
            assertEquals(before, f.local.allAudioAssets().filter { it.documentId == other.id })
            val restored = f.local.allAudioAssets().single { it.documentId == doc.id }
            assertEquals(sentence.id, restored.sentenceId)
            assertEquals(AudioFiles.sha256(recording), AudioFiles.sha256(File(restored.path)))
            val after = f.local.allAudioAssets()
            f.manager.restore(f.prepare())
            assertEquals(after, f.local.allAudioAssets())
        } finally { f.close() }
    }

    @Test fun changedReaderSettingsAfterPreviewRejectBeforeAnyDatabaseCommit() = runBlocking<Unit> {
        val f = Fixture(); val settings = SettingsRepository(context); val original = settings.load()
        try {
            f.source.create("设置预览保护", "正文。", DocumentSource.TXT); f.export()
            val prepared = f.prepare()
            val newer = original.copy(fontSizeSp = if (original.fontSizeSp == 24f) 26f else 24f)
            settings.save(newer, synchronous = true)
            assertTrue("Newer reader settings must require a fresh preview", runCatching {
                f.manager.restore(prepared, restoreSettings = true)
            }.isFailure)
            assertEquals(newer, settings.load()); assertTrue(f.local.all().isEmpty())
        } finally { settings.save(original, synchronous = true); f.close() }
    }

    @Test fun changedCloudPreferencesBeforeCommitRejectBeforeAnyDatabaseCommit() = runBlocking<Unit> {
        val f = Fixture(); val preferences = CloudSpeechPreferencesRepository(context); val original = preferences.load()
        try {
            f.source.create("云设置并发保护", "正文。", DocumentSource.TXT); f.export()
            val prepared = f.prepare()
            val newer = original.copy(enabled = !original.enabled)
            val manager = BackupManager(context, f.local) { if (it == "before_commit") preferences.save(newer, synchronous = true) }
            assertTrue("Concurrent cloud preferences must not be overwritten", runCatching {
                manager.restore(prepared, restoreSettings = true)
            }.isFailure)
            assertEquals(newer, preferences.load()); assertTrue(f.local.all().isEmpty())
        } finally { preferences.save(original, synchronous = true); f.close() }
    }

    @Test fun completeHistoryWithSpacesAndParagraphsPreservesSentenceAndAudioIdentity() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val doc = f.source.create("历史兼容", "First sentence. Second sentence.\n第三句。", DocumentSource.TXT)
            val old = f.source.load(doc.id)!!.content.sentences
            val audio = File(f.directory, "history.wav").also(::writeTestWav)
            f.source.putAudio(SentenceAudio(UUID.randomUUID().toString(), doc.id, old[1].id, AudioKind.RECORDING,
                "local", "self", audio.path, DocumentRepository.textHash(old[1].text), 1000))
            f.source.updateContent(doc.id, doc.title, "当前的新正文。")
            f.export(); f.manager.restore(f.prepare())
            f.local.restoreVersion(doc.id, f.localDb.readerDao().versions(doc.id).single().id)
            assertEquals(old, f.local.load(doc.id)!!.content.sentences)
            val restored = f.local.latestAudio(old[1].id, AudioKind.RECORDING)!!
            assertEquals(AudioFiles.sha256(audio), AudioFiles.sha256(File(restored.path)))
        } finally { f.close() }
    }

    @Test fun validLegacyHistoryWithDifferentSentenceBoundariesAndNullSnapshotsStillParses() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val doc = f.source.create("旧历史兼容", "第一句。第二句。", DocumentSource.TXT)
            f.source.updateContent(doc.id, doc.title, "当前正文。")
            val root = JSONObject(f.source.exportBackup(ReaderSettings()))
            val entry = root.getJSONArray("documents").getJSONObject(0)
            val version = entry.getJSONArray("versions").getJSONObject(0)
            val original = JSONArray(version.getString("sentenceSnapshot"))
            val firstId = original.getJSONObject(0).getString("id")
            val sentences = entry.getJSONArray("sentences")
            val kept = JSONArray()
            for (i in 0 until sentences.length()) {
                val sentence = sentences.getJSONObject(i)
                if (sentence.getString("id") == original.getJSONObject(1).getString("id")) continue
                if (sentence.getString("id") == firstId) sentence.put("text", "第一句。第二句。")
                    .put("textHash", DocumentRepository.textHash("第一句。第二句。"))
                kept.put(sentence)
            }
            entry.put("sentences", kept)
            version.put("sentenceSnapshot", JSONArray().put(JSONObject().put("id", firstId).put("text", "第一句。第二句。")).toString())
            assertEquals(1, f.local.parseBackup(root.toString()).documents.single().versions.size)
            version.put("sentenceSnapshot", JSONObject.NULL)
            assertNull(f.local.parseBackup(root.toString()).documents.single().versions.single().sentenceSnapshot)
        } finally { f.close() }
    }

    @Test fun optingOutOfSettingsRestoreKeepsNewPreferencesAndRestoresDocuments() = runBlocking<Unit> {
        val f = Fixture(); val settings = SettingsRepository(context); val original = settings.load()
        val cloud = CloudSpeechPreferencesRepository(context); val oldCloud = cloud.load()
        try {
            f.source.create("只恢复资料", "正文。", DocumentSource.TXT); f.export()
            val prepared = f.prepare()
            val newer = original.copy(fontSizeSp = if (original.fontSizeSp == 24f) 26f else 24f)
            val newCloud = oldCloud.copy(enabled = !oldCloud.enabled)
            settings.save(newer, true); cloud.save(newCloud, true)
            f.manager.restore(prepared, restoreSettings = false)
            assertEquals(1, f.local.all().size); assertEquals(newer, settings.load()); assertEquals(newCloud, cloud.load())
        } finally { settings.save(original, true); cloud.save(oldCloud, true); f.close() }
    }

    @Test fun settingsChangedAfterCommitArePreservedWithAnExplicitResultWarning() = runBlocking<Unit> {
        val f = Fixture(); val settings = SettingsRepository(context); val original = settings.load()
        try {
            f.source.create("提交后设置保护", "正文。", DocumentSource.TXT); f.export()
            val prepared = f.prepare()
            val newer = original.copy(fontSizeSp = if (original.fontSizeSp == 24f) 26f else 24f)
            val manager = BackupManager(context, f.local) { if (it == "after_commit") settings.save(newer, true) }
            val result = manager.restore(prepared, restoreSettings = true)
            assertEquals(1, f.local.all().size); assertEquals(newer, settings.load()); assertNull(result.settings)
            assertTrue(result.warnings.any { it.contains("保留更新的本机设置") })
        } finally { settings.save(original, true); f.close() }
    }

    @Test fun interruptedCommittedRestoreDoesNotOverwriteNewerCloudPreferencesOnRecovery() = runBlocking<Unit> {
        val f = Fixture(); val cloud = CloudSpeechPreferencesRepository(context); val original = cloud.load()
        try {
            f.source.create("中断设置保护", "正文。", DocumentSource.TXT); f.export()
            val prepared = f.prepare()
            val manager = BackupManager(context, f.local) { if (it == "after_commit") throw IllegalStateException("synthetic interruption") }
            assertTrue(runCatching { manager.restore(prepared, restoreSettings = true) }.isFailure)
            assertEquals(1, f.local.all().size)
            val newer = original.copy(enabled = !original.enabled); cloud.save(newer, true)
            val messages = f.manager.recoverInterruptedRestores()
            assertEquals(newer, cloud.load()); assertTrue(messages.any { it.contains("保留了更新的本机设置") })
            assertFalse(prepared.directory.exists())
        } finally { cloud.save(original, true); f.close() }
    }

    @Test fun partiallyAppliedTargetSettingsCanBeSafelyCompletedAfterInterruption() = runBlocking<Unit> {
        val f = Fixture(); val settings = SettingsRepository(context); val original = settings.load()
        val cloud = CloudSpeechPreferencesRepository(context); val oldCloud = cloud.load()
        try {
            f.source.create("部分设置续接", "正文。", DocumentSource.TXT); f.export()
            val localSettings = original.copy(fontSizeSp = 24f)
            val localCloud = oldCloud.copy(enabled = !oldCloud.enabled)
            settings.save(localSettings, true); cloud.save(localCloud, true)
            val prepared = f.prepare()
            val manager = BackupManager(context, f.local) { if (it == "after_commit") {
                settings.save(ReaderSettings(), true); throw IllegalStateException("synthetic partial settings interruption")
            } }
            assertTrue(runCatching { manager.restore(prepared, restoreSettings = true) }.isFailure)
            f.manager.recoverInterruptedRestores()
            assertEquals(ReaderSettings(), settings.load()); assertEquals(oldCloud, cloud.load())
            assertEquals(1, f.local.all().size); assertFalse(prepared.directory.exists())
        } finally { settings.save(original, true); cloud.save(oldCloud, true); f.close() }
    }

    @Test fun finalSettingsValidationFailureRollsBackAllDatabaseWritesAndReceipt() = runBlocking<Unit> {
        val f = Fixture(); val settings = SettingsRepository(context); val original = settings.load()
        try {
            f.source.create("事务末尾设置保护", "正文。", DocumentSource.TXT)
            val graph = f.local.parseBackup(f.source.exportBackup(ReaderSettings()))
            val guard = BackupSettingsGuard(context); val snapshot = guard.snapshot(); var calls = 0
            val operationId = UUID.randomUUID().toString()
            val newer = original.copy(fontSizeSp = if (original.fontSizeSp == 24f) 26f else 24f)
            assertTrue(runCatching { f.local.restoreBackup(graph, BackupConflictPolicy.KEEP_LOCAL, emptyList(), operationId,
                validateSettings = {
                    if (++calls == 2) settings.save(newer, true)
                    guard.requireUnchanged(snapshot)
                }) }.isFailure)
            assertEquals(2, calls); assertTrue(f.local.all().isEmpty()); assertFalse(f.local.hasRestoreReceipt(operationId))
            assertEquals(newer, settings.load())
        } finally { settings.save(original, true); f.close() }
    }

    @Test fun copiedBookmarkAndAudioCollisionsPreserveUnrelatedOwnersAndReuseTheSameCopy() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val doc = f.source.create("碰撞副本", "备份正文。", DocumentSource.TXT)
            val sentence = f.source.load(doc.id)!!.content.sentences.single()
            f.source.toggleBookmark(doc.id, sentence.id)
            val file = File(f.directory, "copy-source.wav").also(::writeTestWav)
            val sourceAudio = SentenceAudio(UUID.randomUUID().toString(), doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", file.path, DocumentRepository.textHash(sentence.text), 1000)
            f.source.putAudio(sourceAudio); f.export(); f.manager.restore(f.prepare())
            f.local.updateContent(doc.id, doc.title, "较新的本机正文。")
            val graph = f.prepare().graph
            val copyId = stableId("document:${graph.fingerprint}:${doc.id}")
            val copySentence = stableId("sentence:${graph.fingerprint}:$copyId:${sentence.id}")
            val other = f.local.create("副本无关文稿", "无关正文。", DocumentSource.TXT)
            val otherSentence = f.local.load(other.id)!!.content.sentences.single()
            val unrelatedBookmark = BookmarkEntity(stableId("bookmark:$copyId:$copySentence"), other.id, otherSentence.id, 10L)
            f.localDb.readerDao().putBookmark(unrelatedBookmark)
            val old = File(f.directory, "copy-other.wav").also(::writeTestWav)
            val unrelatedAudio = sourceAudio.copy(id = stableId("audio:${graph.fingerprint}:$copyId:${sourceAudio.id}"),
                documentId = other.id, sentenceId = otherSentence.id, textHash = DocumentRepository.textHash(otherSentence.text), path = old.path)
            f.local.putAudio(unrelatedAudio)
            f.manager.restore(f.prepare(), BackupConflictPolicy.COPY)
            assertEquals(3, f.local.all().size)
            assertEquals(listOf(unrelatedBookmark), f.localDb.readerDao().bookmarks(other.id))
            assertEquals(listOf(unrelatedAudio), f.local.allAudioAssets().filter { it.documentId == other.id })
            val before = f.local.allAudioAssets()
            f.manager.restore(f.prepare(), BackupConflictPolicy.COPY)
            assertEquals(3, f.local.all().size); assertEquals(before, f.local.allAudioAssets())
            assertEquals(sentence.text, f.local.load(copyId)!!.content.sentences.single().text)
        } finally { f.close() }
    }
}
