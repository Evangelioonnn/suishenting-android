package com.museum.reader.data

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.data.db.EditorDraftEntity
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database() = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()

    @Test fun restoreRepairsCorruptLocalAudioAndPersistsSelectedSettings() = runBlocking<Unit> {
        val sourceDb = database(); val targetDb = database()
        val source = DocumentRepository(context, sourceDb); val target = DocumentRepository(context, targetDb)
        val packageFile = File(context.cacheDir, UUID.randomUUID().toString() + ".museumreader")
        val audioFile = File(context.filesDir, "sentence-audio/test-" + UUID.randomUUID() + "/voice.wav")
        val settings = SettingsRepository(context); val previousSettings = settings.load()
        val cloud = com.museum.reader.cloud.CloudSpeechPreferencesRepository(context); val previousCloud = cloud.load()
        try {
            val doc = source.create("修复音频", "恢复测试。", DocumentSource.TXT)
            val sentence = source.load(doc.id)!!.content.sentences.single()
            writeTestWav(audioFile)
            source.putAudio(SentenceAudio("repair-audio", doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", audioFile.path, DocumentRepository.textHash(sentence.text), 1000))
            cloud.save(previousCloud.copy(enabled = true, primaryProvider = com.museum.reader.cloud.CloudProvider.BAIDU),
                synchronous = true)
            BackupManager(context, source).export(Uri.fromFile(packageFile), ReaderSettings(speechRate = 0.8f, pitch = 1.1f))
            val manager = BackupManager(context, target)
            manager.restore(manager.prepare(Uri.fromFile(packageFile)))
            val corrupt = File(target.audioAssets(doc.id).single().path)
            corrupt.writeText("corrupted local file")
            cloud.save(previousCloud.copy(enabled = false), synchronous = true)
            manager.restore(manager.prepare(Uri.fromFile(packageFile)), restoreSettings = true)
            val repaired = File(target.audioAssets(doc.id).single().path)
            assertNotEquals(corrupt.canonicalPath, repaired.canonicalPath)
            assertEquals(AudioFiles.sha256(audioFile), AudioFiles.sha256(repaired))
            assertEquals(0.8f, settings.load().speechRate, 0.001f)
            assertEquals(1.1f, settings.load().pitch, 0.001f)
            assertTrue(cloud.load().enabled)
            assertEquals(com.museum.reader.cloud.CloudProvider.BAIDU, cloud.load().primaryProvider)
            assertTrue(corrupt.exists()) // Previous file is kept for explicit storage maintenance.
            corrupt.delete(); corrupt.parentFile?.delete()
        } finally {
            target.allAudioAssets().forEach { File(it.path).let { f -> f.delete(); f.parentFile?.delete() } }
            settings.save(previousSettings, synchronous = true); cloud.save(previousCloud, synchronous = true)
            packageFile.delete(); audioFile.delete(); audioFile.parentFile?.delete()
            sourceDb.close(); targetDb.close()
        }
    }

    @Test fun completePackageRoundTripRetainsAudioHistoryDraftAndRoute() = runBlocking {
        val sourceDb = database()
        val targetDb = database()
        val source = DocumentRepository(context, sourceDb)
        val target = DocumentRepository(context, targetDb)
        val packageFile = File(context.cacheDir, UUID.randomUUID().toString() + ".museumreader")
        val sourceAudio = File(context.filesDir, "sentence-audio/test-" + UUID.randomUUID() + "/speech.wav")
        val created = ArrayList<File>()
        try {
            val document = source.create("备份测试", "开场。第一句。\n\n相同句。相同句。结束。", DocumentSource.TXT)
            val original = source.load(document.id)!!.content.sentences
            writeTestWav(sourceAudio)
            source.putAudio(SentenceAudio("recording", document.id, original[0].id, AudioKind.RECORDING,
                "local", "self", sourceAudio.path, DocumentRepository.textHash(original[0].text), 1000))
            source.toggleBookmark(document.id, original[1].id)
            source.savePlaylist(null, "路线", listOf(document.id))
            source.updateContent(document.id, document.title, document.text + "新增。")
            source.setPinned(document.id, true)
            source.savePosition(document.id, 3)
            sourceDb.readerDao().putDraft(EditorDraftEntity(document.id, "未保存标题", "未保存草稿。", 2, 2, 1))
            BackupManager(context, source).export(Uri.fromFile(packageFile), ReaderSettings(speechRate = 0.8f))
            val manager = BackupManager(context, target)
            val prepared = manager.prepare(Uri.fromFile(packageFile))
            assertEquals(1, prepared.preview.newDocuments)
            assertEquals(1, prepared.preview.audioCount)
            assertEquals(0.8f, prepared.graph.settings!!.speechRate, 0.001f)
            assertTrue(prepared.preview.warnings.isEmpty())
            manager.restore(prepared)
            val restored = target.load(document.id)!!
            assertEquals(source.find(document.id), restored.document)
            assertEquals(source.bookmarks(document.id).map { it.sentenceId }, target.bookmarks(document.id).map { it.sentenceId })
            assertEquals(original.map { it.id }, restored.content.sentences.take(original.size).map { it.id })
            assertEquals("未保存草稿。", targetDb.readerDao().draft(document.id)!!.text)
            assertEquals(1, target.versions(document.id).size)
            assertEquals(listOf(document.id), target.playlists().single().documentIds)
            val imported = target.audioAssets(document.id).single()
            created += File(imported.path)
            assertEquals(AudioFiles.sha256(sourceAudio), AudioFiles.sha256(File(imported.path)))
            assertNotNull(AudioFiles.inspect(File(imported.path)))
            val again = manager.prepare(Uri.fromFile(packageFile))
            manager.restore(again)
            assertEquals(1, target.all().size)
            assertEquals(1, target.audioAssets(document.id).size)
        } finally {
            (created + target.allAudioAssets().map { File(it.path) }).distinctBy { it.path }.forEach { it.delete(); it.parentFile?.delete() }
            sourceDb.close(); targetDb.close(); packageFile.delete()
            sourceAudio.delete(); sourceAudio.parentFile?.delete()
        }
    }

    @Test fun conflictsKeepLocalCopyIdempotentlyAndReplaceWithRecoveryPoint() = runBlocking {
        val sourceDb = database()
        val targetDb = database()
        val source = DocumentRepository(context, sourceDb)
        val target = DocumentRepository(context, targetDb)
        val packageFile = File(context.cacheDir, UUID.randomUUID().toString() + ".json")
        val recoveryPoints = ArrayList<File>()
        try {
            val document = source.create("冲突测试", "备份内容。", DocumentSource.TXT)
            packageFile.writeText(source.exportBackup(ReaderSettings()))
            target.importBackup(packageFile.readText())
            target.updateContent(document.id, document.title, "本机更新内容。")
            val manager = BackupManager(context, target)
            val keep = manager.prepare(Uri.fromFile(packageFile))
            assertEquals(1, keep.preview.conflictTitles.size)
            manager.restore(keep)
            assertEquals("本机更新内容。", target.find(document.id)!!.text)
            manager.restore(manager.prepare(Uri.fromFile(packageFile)), BackupConflictPolicy.COPY)
            manager.restore(manager.prepare(Uri.fromFile(packageFile)), BackupConflictPolicy.COPY)
            assertEquals(2, target.all().size)
            val replacement = manager.restore(manager.prepare(Uri.fromFile(packageFile)), BackupConflictPolicy.REPLACE)
            assertEquals("备份内容。", target.find(document.id)!!.text)
            val point = File(replacement.recoveryPoint!!)
            recoveryPoints += point
            assertTrue(point.isFile)
            val pointPreview = manager.prepare(Uri.fromFile(point))
            assertEquals("本机更新内容。", pointPreview.graph.documents.first { it.document.id == document.id }.document.text)
            manager.discard(pointPreview)
        } finally {
            sourceDb.close(); targetDb.close(); packageFile.delete(); recoveryPoints.forEach { it.delete() }
        }
    }

    @Test fun interruptedBeforeCommitKeepsOriginalDataAndAfterCommitCanFinish() = runBlocking {
        val sourceDb = database()
        val targetDb = database()
        val source = DocumentRepository(context, sourceDb)
        val target = DocumentRepository(context, targetDb)
        val packageFile = File(context.cacheDir, UUID.randomUUID().toString() + ".museumreader")
        val sourceAudio = File(context.filesDir, "sentence-audio/test-" + UUID.randomUUID() + "/speech.wav")
        try {
            val document = source.create("中断测试", "第一句。", DocumentSource.TXT)
            val sentence = source.load(document.id)!!.content.sentences.first()
            writeTestWav(sourceAudio)
            source.putAudio(SentenceAudio("audio", document.id, sentence.id, AudioKind.RECORDING,
                "local", "self", sourceAudio.path, DocumentRepository.textHash(sentence.text), 1000))
            BackupManager(context, source).export(Uri.fromFile(packageFile), ReaderSettings())
            val before = BackupManager(context, target) { point -> if (point == "before_commit") error("injected failure") }
            assertTrue(runCatching { before.restore(before.prepare(Uri.fromFile(packageFile))) }.isFailure)
            assertTrue(target.all().isEmpty())
            assertTrue(sourceAudio.isFile)
            val after = BackupManager(context, target) { point -> if (point == "after_commit") error("injected interruption") }
            assertTrue(runCatching { after.restore(after.prepare(Uri.fromFile(packageFile))) }.isFailure)
            assertEquals(1, target.all().size)
            val manager = BackupManager(context, target)
            assertTrue(manager.recoverInterruptedRestores().isNotEmpty())
            assertTrue(manager.recoverInterruptedRestores().isEmpty())
            assertNotNull(AudioFiles.inspect(File(target.audioAssets(document.id).single().path)))
        } finally {
            target.allAudioAssets().forEach { File(it.path).let { file -> file.delete(); file.parentFile?.delete() } }
            sourceDb.close(); targetDb.close(); packageFile.delete(); sourceAudio.delete(); sourceAudio.parentFile?.delete()
        }
    }

    @Test fun unsafePackageIsRejectedAndBadAudioRequiresAcknowledgment() = runBlocking {
        val sourceDb = database()
        val targetDb = database()
        val source = DocumentRepository(context, sourceDb)
        val target = DocumentRepository(context, targetDb)
        val files = ArrayList<File>()
        val sourceAudio = File(context.filesDir, "sentence-audio/test-" + UUID.randomUUID() + "/speech.wav")
        try {
            val document = source.create("安全测试", "第一句。", DocumentSource.TXT)
            val sentence = source.load(document.id)!!.content.sentences.first()
            writeTestWav(sourceAudio)
            source.putAudio(SentenceAudio("audio", document.id, sentence.id, AudioKind.RECORDING,
                "local", "self", sourceAudio.path, DocumentRepository.textHash(sentence.text), 1000))
            val valid = File(context.cacheDir, UUID.randomUUID().toString()).also { files += it }
            BackupManager(context, source).export(Uri.fromFile(valid), ReaderSettings())
            val unsafe = File(context.cacheDir, UUID.randomUUID().toString()).also { files += it }
            ZipOutputStream(unsafe.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("audio/../../outside.wav"))
                zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
            }
            val manager = BackupManager(context, target)
            assertTrue(runCatching { manager.prepare(Uri.fromFile(unsafe)) }.isFailure)
            assertTrue(target.all().isEmpty())
            val bad = File(context.cacheDir, UUID.randomUUID().toString()).also { files += it }
            rewriteZip(valid, bad) { root ->
                root.getJSONArray("audio").getJSONObject(0).put("sha256", "0".repeat(64))
            }
            val prepared = manager.prepare(Uri.fromFile(bad))
            assertEquals(1, prepared.preview.warnings.size)
            assertTrue(runCatching { manager.restore(prepared) }.isFailure)
            assertTrue(target.all().isEmpty())
            val result = manager.restore(prepared, acceptWarnings = true)
            assertEquals(1, result.warnings.size)
            assertEquals(1, target.all().size)
            assertTrue(target.allAudioAssets().isEmpty())
        } finally {
            sourceDb.close(); targetDb.close(); files.forEach { it.delete() }; sourceAudio.delete(); sourceAudio.parentFile?.delete()
        }
    }

    @Test fun legacyAudioRecoveryUsesSentenceHashAndDoesNotGuessUnknownFiles() = runBlocking {
        val db = database()
        val repository = DocumentRepository(context, db)
        var folder: File? = null
        try {
            val document = repository.create("录音恢复", "第一句。", DocumentSource.TXT)
            val sentence = repository.load(document.id)!!.content.sentences.first()
            folder = File(context.filesDir, "sentence-audio/recordings/" + document.id)
            val valid = File(folder, sentence.id + "-recording-" + DocumentRepository.textHash(sentence.text).take(12) + ".m4a")
            val unknown = File(folder, sentence.id + "-recording-000000000000.m4a")
            writeTestWav(valid); writeTestWav(unknown)
            val report = repository.recoverLegacyAudio(document.id)
            assertEquals(1, report.recovered)
            assertEquals(listOf(unknown.canonicalPath), report.unmatched.map { it.canonicalPath })
            assertEquals(valid.canonicalPath, File(repository.latestAudio(sentence.id, AudioKind.RECORDING)!!.path).canonicalPath)
            assertTrue(unknown.isFile)
            assertEquals(0, repository.recoverLegacyAudio(document.id).recovered)
        } finally {
            db.close(); folder?.listFiles().orEmpty().forEach { it.delete() }; folder?.delete()
        }
    }

    @Test fun oldJsonEnglishLayoutCanBeLoadedAndExportedAgain() = runBlocking {
        val db = database()
        val repository = DocumentRepository(context, db)
        try {
            val document = repository.create("English", "First. Second.", DocumentSource.TXT)
            val root = JSONObject(repository.exportBackup(ReaderSettings())).put("version", 1)
            root.getJSONArray("documents").getJSONObject(0).put("text", "First.Second.")
            repository.updateContent(document.id, document.title, "Different.")
            repository.importBackup(root.toString(), BackupConflictPolicy.REPLACE)
            assertEquals(listOf("First.", "Second."), repository.load(document.id)!!.content.sentences.map { it.text })
            val exported = repository.exportBackup(ReaderSettings())
            assertNotNull(repository.parseBackup(exported))
        } finally { db.close() }
    }

    @Test fun missingAudioAndDuplicateDescriptorsAreReportedBeforeCommit() = runBlocking {
        val sourceDb = database()
        val targetDb = database()
        val source = DocumentRepository(context, sourceDb)
        val target = DocumentRepository(context, targetDb)
        val files = ArrayList<File>()
        val sourceAudio = File(context.filesDir, "sentence-audio/test-" + UUID.randomUUID() + "/speech.wav")
        try {
            val document = source.create("缺失音频", "第一句。", DocumentSource.TXT)
            val sentence = source.load(document.id)!!.content.sentences.first()
            writeTestWav(sourceAudio)
            source.putAudio(SentenceAudio("audio", document.id, sentence.id, AudioKind.RECORDING,
                "local", "self", sourceAudio.path, DocumentRepository.textHash(sentence.text), 1000))
            val valid = File(context.cacheDir, UUID.randomUUID().toString()).also { files += it }
            BackupManager(context, source).export(Uri.fromFile(valid), ReaderSettings())
            val missing = File(context.cacheDir, UUID.randomUUID().toString()).also { files += it }
            rewriteZip(valid, missing, omitAudio = true) {}
            val manager = BackupManager(context, target)
            val prepared = manager.prepare(Uri.fromFile(missing))
            assertEquals(1, prepared.preview.warnings.size)
            assertEquals(0, prepared.preview.audioCount)
            assertTrue(target.all().isEmpty())
            manager.discard(prepared)
            val duplicate = File(context.cacheDir, UUID.randomUUID().toString()).also { files += it }
            rewriteZip(valid, duplicate) { root ->
                val audio = root.getJSONArray("audio")
                audio.put(JSONObject(audio.getJSONObject(0).toString()))
            }
            assertTrue(runCatching { manager.prepare(Uri.fromFile(duplicate)) }.isFailure)
            assertTrue(target.all().isEmpty())
        } finally {
            sourceDb.close(); targetDb.close(); files.forEach { it.delete() }; sourceAudio.delete(); sourceAudio.parentFile?.delete()
        }
    }

    @Test fun oversizedManifestIsRejectedWithoutChangingDocuments() = runBlocking {
        val db = database()
        val repository = DocumentRepository(context, db)
        val file = File(context.cacheDir, UUID.randomUUID().toString())
        try {
            val document = repository.create("保留", "原有内容。", DocumentSource.TXT)
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                val chunk = ByteArray(64 * 1024) { 'A'.code.toByte() }
                repeat(481) { zip.write(chunk) }
                zip.closeEntry()
            }
            assertTrue(runCatching { BackupManager(context, repository).prepare(Uri.fromFile(file)) }.isFailure)
            assertEquals("原有内容。", repository.find(document.id)!!.text)
        } finally { db.close(); file.delete() }
    }

    private fun rewriteZip(source: File, target: File, omitAudio: Boolean = false, update: (JSONObject) -> Unit) {
        ZipInputStream(source.inputStream()).use { input ->
            ZipOutputStream(target.outputStream()).use { output ->
                var entry = input.nextEntry
                while (entry != null) {
                    val bytes = input.readBytes()
                    val result = if (entry.name == "manifest.json") {
                        JSONObject(bytes.toString(Charsets.UTF_8)).also(update).toString().toByteArray()
                    } else bytes
                    if (!omitAudio || !entry.name.startsWith("audio/")) {
                        output.putNextEntry(ZipEntry(entry.name)); output.write(result); output.closeEntry()
                    }
                    input.closeEntry(); entry = input.nextEntry
                }
            }
        }
    }
}
