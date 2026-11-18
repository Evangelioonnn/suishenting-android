package com.museum.reader.recording

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.data.*
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecordingIdentityRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun tone(file: File, different: Boolean = false): File {
        writeTestWav(file)
        if (different) file.writeBytes(file.readBytes().also { it[46] = (it[46].toInt() xor 1).toByte() })
        return file
    }

    @Test fun confirmingAndRestoringRecordingSelectsOnlyOneTakeAcrossLegacyProviderLabels() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build(); val repo = DocumentRepository(context, db)
        val dir = File(context.filesDir, "sentence-audio/recording-identity-${UUID.randomUUID()}").apply { mkdirs() }
        var journal: File? = null
        try {
            val doc = repo.create("旧录音标签替换", "同一句必须播放已确认的新录音。", DocumentSource.TXT)
            val sentence = repo.load(doc.id)!!.content.sentences.single(); val hash = DocumentRepository.textHash(sentence.text)
            val oldFile = tone(File(dir, "old.wav")); val newFile = tone(File(dir, "new.wav"), true)
            val oldHash = AudioFiles.sha256(oldFile); val newHash = AudioFiles.sha256(newFile); assertNotEquals(oldHash, newHash)
            val old = SentenceAudio(UUID.randomUUID().toString(), doc.id, sentence.id, AudioKind.RECORDING,
                null, null, oldFile.path, hash, 1000)
            repo.putAudio(old)
            val pending = PendingRecording(UUID.randomUUID().toString(), doc.id, sentence.id, hash, newFile.path, 1000, true)
            journal = writeTestJournal(context, pending)
            val saved = SentenceRecordingManager(context, repo).save(pending)
            var assets = repo.allAudioAssets()
            assertEquals("Only the confirmed take may remain current", listOf(saved.id), assets.filter { it.archivedAt == null }.map { it.id })
            assertNotNull(assets.single { it.id == old.id }.archivedAt)
            assertEquals(oldHash, AudioFiles.sha256(oldFile)); assertEquals(newHash, AudioFiles.sha256(newFile))
            StorageMaintenance(context, repo, db).restoreAudio(old.id)
            assets = repo.allAudioAssets()
            assertEquals(listOf(old.id), assets.filter { it.archivedAt == null }.map { it.id })
            assertNotNull(assets.single { it.id == saved.id }.archivedAt)
            assertEquals(oldHash, AudioFiles.sha256(oldFile)); assertEquals(newHash, AudioFiles.sha256(newFile))
        } finally { journal?.delete(); dir.listFiles().orEmpty().forEach { it.delete() }; dir.delete(); db.close() }
    }

    @Test fun automaticLegacyFileRecoveryCannotPromoteAnOldTakeBesideAnExistingConfirmedRecording() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build(); val repo = DocumentRepository(context, db)
        val owned = mutableListOf<File>()
        try {
            val doc = repo.create("自动找回保护", "同一句已确认录音应保持当前。", DocumentSource.TXT)
            val sentence = repo.load(doc.id)!!.content.sentences.single(); val hash = DocumentRepository.textHash(sentence.text)
            val currentFile = tone(File(context.filesDir, "sentence-audio/recording-current-${UUID.randomUUID()}/current.wav"), true).also { owned += it }
            val current = SentenceAudio(UUID.randomUUID().toString(), doc.id, sentence.id, AudioKind.RECORDING,
                "legacy-recorder", "legacy-self", currentFile.path, hash, 1000)
            repo.putAudio(current)
            val olderFile = tone(File(context.filesDir, "sentence-audio/recordings/${doc.id}/${sentence.id}-recording-${hash.take(12)}-${UUID.randomUUID()}.wav")).also { owned += it }
            assertTrue(olderFile.setLastModified(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000))
            val before = AudioFiles.sha256(currentFile)
            val restarted = DocumentRepository(context, db)
            assertNotNull(restarted.load(doc.id))
            val assets = restarted.allAudioAssets(); assertEquals(2, assets.size)
            assertEquals("Automatic discovery must preserve the already confirmed output", listOf(current.id), assets.filter { it.archivedAt == null }.map { it.id })
            assertNotNull(assets.single { File(it.path).canonicalPath == olderFile.canonicalPath }.archivedAt)
            assertEquals(before, AudioFiles.sha256(currentFile)); assertTrue(olderFile.isFile)
        } finally { owned.forEach { it.delete(); it.parentFile?.delete() }; db.close() }
    }
}
