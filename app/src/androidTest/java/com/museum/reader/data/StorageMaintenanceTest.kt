package com.museum.reader.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageMaintenanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun fixture(block: suspend (ReaderDatabase, DocumentRepository, File) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        val dir = File(context.filesDir, "sentence-audio/test-" + UUID.randomUUID()).apply { mkdirs() }
        try { block(db, repo, dir) }
        finally {
            dir.listFiles().orEmpty().forEach { it.delete() }; dir.delete()
            StorageMaintenance(context, repo, db).recover()
            db.close()
        }
    }

    @Test fun cloudCleanupCountsRealFilesAndCannotTouchRecordingOrEscapeOwnedPath() = runBlocking<Unit> {
        fixture { db, repo, dir ->
            val doc = repo.create("存储测试", "测试句。", DocumentSource.TXT)
            val sentence = repo.load(doc.id)!!.content.sentences.single()
            val recording = File(dir, "recording.wav"); val cloud = File(dir, "cloud.wav")
            writeTestWav(recording); writeTestWav(cloud)
            val hash = DocumentRepository.textHash(sentence.text)
            val recorded = SentenceAudio("recorded", doc.id, sentence.id, AudioKind.RECORDING, "local", "self", recording.path, hash)
            val cached = SentenceAudio("cloud", doc.id, sentence.id, AudioKind.CLOUD, "XUNFEI", "voice", cloud.path, hash)
            repo.putAudio(recorded); repo.putAudio(cached)
            val manager = StorageMaintenance(context, repo, db)
            val stats = manager.overview()
            assertEquals(recording.length(), stats.recordingBytes)
            assertEquals(cloud.length(), stats.cloudBytes)
            assertTrue(runCatching { manager.cleanupAudio(listOf(recorded), cloudOnly = true) }.isFailure)
            val result = manager.cleanupAudio(listOf(cached), cloudOnly = true)
            assertEquals(1, result.deletedFiles)
            assertTrue(recording.isFile)
            assertFalse(cloud.exists())
            assertEquals(listOf("recorded"), repo.allAudioAssets().map { it.id })
            val outside = File(context.cacheDir, "outside-" + UUID.randomUUID() + ".wav")
            try {
                writeTestWav(outside)
                assertTrue(runCatching { manager.cleanupUnlinked(UnlinkedAudio(outside.path, outside.length(), outside.lastModified())) }.isFailure)
                assertTrue(outside.exists())
            } finally { outside.delete() }
        }
    }

    @Test fun trashSurvivesRestartRequiresSevenDaysAndPurgeCascadesRouteOnlyAfterConfirmation() = runBlocking<Unit> {
        fixture { db, repo, dir ->
            val doc = repo.create("最近删除", "保留文稿。", DocumentSource.TXT)
            val second = repo.create("下一站", "另一文稿。", DocumentSource.TXT)
            val sentence = repo.load(doc.id)!!.content.sentences.single()
            val file = File(dir, "recorded.wav"); writeTestWav(file)
            repo.putAudio(SentenceAudio("recorded", doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", file.path, DocumentRepository.textHash(sentence.text)))
            val route = repo.savePlaylist(null, "路线", listOf(doc.id, second.id))
            repo.savePlaylistPosition(route.id, second.id)
            repo.savePlaylist(route.id, route.title, listOf(second.id, doc.id))
            assertEquals(second.id, repo.playlists().single().lastDocumentId)
            repo.trash(doc.id)
            assertNull(repo.find(doc.id))
            assertEquals(listOf(second.id, doc.id), repo.playlists().single().documentIds)
            val manager = StorageMaintenance(context, repo, db)
            val deleted = manager.overview().trash.single()
            assertTrue(runCatching { manager.purge(deleted) }.isFailure)
            repo.restore(doc.id)
            assertNotNull(repo.find(doc.id))
            repo.trash(doc.id)
            val current = StorageMaintenance(context, DocumentRepository(context, db), db).overview().trash.single()
            manager.purge(current, now = current.trashedAt + StorageMaintenance.RETENTION_MS)
            assertFalse(file.exists())
            assertEquals(listOf(second.id), repo.playlists().single().documentIds)
            assertEquals(second.id, repo.all().single().id)
        }
    }

    @Test fun committedCleanupInterruptionResumesAndUnlinkedFilesRemainUntilExplicitCleanup() = runBlocking<Unit> {
        fixture { db, repo, dir ->
            val doc = repo.create("中断清理", "测试句。", DocumentSource.TXT)
            val sentence = repo.load(doc.id)!!.content.sentences.single()
            val file = File(dir, "cloud.wav"); writeTestWav(file)
            val audio = SentenceAudio("cloud", doc.id, sentence.id, AudioKind.CLOUD, "XUNFEI", "voice",
                file.path, DocumentRepository.textHash(sentence.text))
            repo.putAudio(audio)
            val failing = StorageMaintenance(context, repo, db) { if (it == "after_commit") error("injected interruption") }
            assertTrue(runCatching { failing.cleanupAudio(listOf(audio), cloudOnly = true) }.isFailure)
            assertTrue(repo.allAudioAssets().isEmpty())
            assertTrue(file.exists())
            val manager = StorageMaintenance(context, repo, db)
            assertTrue(manager.recover().isNotEmpty())
            assertFalse(file.exists())
            val unknown = File(dir, "unknown.wav"); writeTestWav(unknown)
            val found = manager.overview().unlinked.first { File(it.path).canonicalPath == unknown.canonicalPath }
            assertTrue(unknown.exists())
            manager.cleanupUnlinked(found)
            assertFalse(unknown.exists())
        }
    }
}
