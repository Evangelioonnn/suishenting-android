package com.museum.reader.recording

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.data.*
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingProtectionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun reRecordingKeepsFullRawBytesAndArchivesOldRecordingOnlyAfterConfirmation() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        val dir = File(context.filesDir, "sentence-audio/test-" + UUID.randomUUID())
        try {
            val doc = repo.create("重录保护", "保留首尾字。", DocumentSource.TXT)
            val sentence = repo.load(doc.id)!!.content.sentences.single()
            val old = File(dir, "old.wav"); val take = File(dir, "new.wav")
            writeTestWav(old); writeTestWav(take)
            repo.putAudio(SentenceAudio("original", doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", old.path, DocumentRepository.textHash(sentence.text), 1000))
            val manager = SentenceRecordingManager(context, repo)
            val id = UUID.randomUUID().toString()
            val pending = PendingRecording(id, doc.id, sentence.id, DocumentRepository.textHash(sentence.text), take.path, 1000, true)
            val before = AudioFiles.sha256(take)
            assertEquals("original", repo.latestAudio(sentence.id, AudioKind.RECORDING)!!.id)
            manager.save(pending)
            assertEquals(before, AudioFiles.sha256(File(repo.latestAudio(sentence.id, AudioKind.RECORDING)!!.path)))
            assertEquals(id, repo.latestAudio(sentence.id, AudioKind.RECORDING)!!.id)
            assertTrue(old.isFile)
            assertNotNull(repo.audioAssets(doc.id).first { it.id == "original" }.archivedAt)
        } finally {
            dir.listFiles().orEmpty().forEach { it.delete() }; dir.delete(); db.close()
        }
    }

    @Test fun crediblePendingTakeIsNeverAutomaticallyActivatedByLegacyRecovery() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        val pendingId = UUID.randomUUID().toString()
        val journal = File(context.filesDir, "recording-pending/$pendingId.json")
        var file: File? = null
        try {
            val doc = repo.create("待确认隔离", "第一份录音。", DocumentSource.TXT)
            val sentence = repo.load(doc.id)!!.content.sentences.single()
            val hash = DocumentRepository.textHash(sentence.text)
            file = File(context.filesDir, "sentence-audio/recordings/${doc.id}/${sentence.id}-recording-${hash.take(12)}-$pendingId.m4a")
            writeTestWav(file)
            AudioFiles.writeAtomic(journal, JSONObject().put("documentId", doc.id).put("sentenceId", sentence.id)
                .put("textHash", hash).put("path", file.path).toString())
            DocumentRepository(context, db).load(doc.id)
            assertTrue(repo.audioAssets(doc.id).isEmpty())
            assertTrue(file.exists())
            journal.writeText("damaged pending journal")
            DocumentRepository(context, db).load(doc.id)
            assertTrue(repo.audioAssets(doc.id).isEmpty())
            assertTrue(file.exists())
        } finally { journal.delete(); file?.delete(); file?.parentFile?.delete(); db.close() }
    }

    @Test fun cancelledOrInvalidTakeCannotOverwriteOriginalAndPendingJournalCanBeRecovered() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        val dir = File(context.filesDir, "sentence-audio/test-" + UUID.randomUUID())
        val journalId = UUID.randomUUID().toString()
        val journal = File(context.filesDir, "recording-pending/$journalId.json")
        try {
            val doc = repo.create("取消保护", "原录音。", DocumentSource.TXT)
            val sentence = repo.load(doc.id)!!.content.sentences.single()
            val old = File(dir, "old.wav"); val bad = File(dir, "bad.m4a")
            writeTestWav(old); bad.writeText("invalid")
            val hash = DocumentRepository.textHash(sentence.text)
            repo.putAudio(SentenceAudio("original", doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", old.path, hash, 1000))
            AudioFiles.writeAtomic(journal, JSONObject().put("documentId", doc.id).put("sentenceId", sentence.id)
                .put("textHash", hash).put("path", bad.path).toString())
            val manager = SentenceRecordingManager(context, repo)
            val pending = manager.pendingRecordings().first { it.id == journalId }
            assertFalse(pending.playable)
            assertTrue(runCatching { manager.save(pending) }.isFailure)
            assertTrue(bad.exists())
            assertEquals("original", repo.latestAudio(sentence.id, AudioKind.RECORDING)!!.id)
            manager.discard(pending)
            assertFalse(bad.exists())
            assertTrue(old.exists())
            assertEquals("original", repo.latestAudio(sentence.id, AudioKind.RECORDING)!!.id)
        } finally {
            journal.delete(); dir.listFiles().orEmpty().forEach { it.delete() }; dir.delete(); db.close()
        }
    }
}
