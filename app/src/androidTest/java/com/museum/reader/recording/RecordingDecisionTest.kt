package com.museum.reader.recording

import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.data.*
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecordingDecisionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun delayedAndConcurrentDecisionsPreserveTheSelectedTakeAndSharedFiles() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        val dir = File(context.filesDir, "sentence-audio/decision-${UUID.randomUUID()}")
        val journals = mutableListOf<File>()
        try {
            val doc = repo.create("确认或放弃", "原录音所属句。", DocumentSource.TXT)
            val sentence = repo.load(doc.id)!!.content.sentences.single()
            val hash = DocumentRepository.textHash(sentence.text)
            val first = SentenceRecordingManager(context, repo)
            val second = SentenceRecordingManager(context, repo)
            fun take(file: File? = null): PendingRecording {
                val id = UUID.randomUUID().toString()
                val wav = file ?: File(dir, "$id.wav").also {
                    writeTestWav(it)
                    it.writeBytes(it.readBytes().apply { this[46] = journals.size.toByte() })
                }
                return PendingRecording(id, doc.id, sentence.id, hash, wav.path, 1000, true)
                    .also { journals += writeTestJournal(context, it) }
            }
            val original = take()
            val originalHash = AudioFiles.sha256(File(original.path))
            first.save(original)
            assertTrue(runCatching { second.discard(original) }.isFailure)
            val cancelled = take()
            first.discard(cancelled)
            assertTrue(runCatching { second.save(cancelled) }.isFailure)
            assertFalse(File(cancelled.path).exists())
            assertEquals(original.id, repo.latestAudio(sentence.id, AudioKind.RECORDING)?.id)

            val shared = take(File(original.path))
            second.discard(shared)
            assertEquals(originalHash, AudioFiles.sha256(File(original.path)))
            val onlyPending = take()
            val sharesPendingFile = take(File(onlyPending.path))
            second.discard(sharesPendingFile)
            assertTrue(File(onlyPending.path).isFile)
            first.discard(onlyPending)

            val raced = take()
            val rawHash = AudioFiles.sha256(File(raced.path))
            val start = CompletableDeferred<Unit>()
            val save = async(Dispatchers.IO) { start.await(); runCatching { first.save(raced) }.isSuccess }
            val discard = async(Dispatchers.IO) { start.await(); runCatching { second.discard(raced) }.isSuccess }
            start.complete(Unit)
            val saved = save.await()
            assertNotEquals(saved, discard.await())
            val active = repo.latestAudio(sentence.id, AudioKind.RECORDING)
            assertEquals(if (saved) raced.id else original.id, active?.id)
            if (saved) assertEquals(rawHash, AudioFiles.sha256(File(raced.path))) else assertFalse(File(raced.path).exists())
            assertEquals(originalHash, AudioFiles.sha256(File(original.path)))

            val interrupted = take()
            val committed = SentenceAudio(interrupted.id, doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", interrupted.path, hash, 1000)
            repo.confirmRecording(committed) // The process stopped before the decision journal was unlinked.
            val interruptedHash = AudioFiles.sha256(File(interrupted.path))
            val newer = take()
            first.save(newer)
            assertTrue(runCatching { second.discard(interrupted) }.isFailure)
            second.inventory(doc.id)
            assertTrue(runCatching { second.save(interrupted) }.isFailure)
            assertEquals(newer.id, repo.latestAudio(sentence.id, AudioKind.RECORDING)?.id)
            assertNotNull(repo.recordingById(interrupted.id)?.archivedAt)
            assertEquals(interruptedHash, AudioFiles.sha256(File(interrupted.path)))
            val removed = take()
            repo.releasePendingRecording(committed.copy(id = removed.id, path = removed.path))
            second.inventory(doc.id)
            assertFalse(File(removed.path).exists())
            assertEquals(newer.id, repo.latestAudio(sentence.id, AudioKind.RECORDING)?.id)
        } finally {
            journals.forEach { it.delete() }
            dir.listFiles().orEmpty().forEach { it.delete() }; dir.delete(); db.close()
        }
    }

    @Test fun damagedJournalIsVisibleAndProtectsOriginalsThroughBackupAndRestore() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val targetDb = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        val targetRepo = DocumentRepository(context, targetDb)
        val id = UUID.randomUUID().toString()
        val journal = File(context.filesDir, "recording-pending/$id.json")
        val isolated = File(journal.parentFile, journal.name + ".quarantined")
        val zip = File(context.cacheDir, "$id.museumreader")
        val created = mutableListOf<File>()
        try {
            val doc = repo.create("备份保留", "仍然可以恢复的正文。", DocumentSource.TXT)
            val sentence = repo.load(doc.id)!!.content.sentences.single()
            val hash = DocumentRepository.textHash(sentence.text)
            val wav = File(context.filesDir, "sentence-audio/recordings/${doc.id}/${sentence.id}-recording-${hash.take(12)}-$id.wav")
            created += wav
            writeTestWav(wav)
            val wavHash = AudioFiles.sha256(wav)
            val manager = SentenceRecordingManager(context, repo)
            val backup = BackupManager(context, repo)
            val storage = StorageMaintenance(context, repo, db)
            val uri = Uri.fromFile(zip)
            writeTestJournal(context, PendingRecording(id, doc.id, sentence.id, hash, wav.path, 1000, true))
            assertEquals(sentence.text, manager.inventory(doc.id).pending.single { it.id == id }.excerpt)
            assertTrue(runCatching { backup.export(uri, ReaderSettings()) }.isFailure)
            journal.writeText("damaged")
            val damage = manager.inventory().damaged.single { it.name == journal.name }
            assertFalse(damage.isolated)
            assertEquals(listOf(wav.canonicalPath), damage.originals)
            assertTrue(runCatching { backup.export(uri, ReaderSettings()) }.isFailure)
            journal.appendText(" changed")
            assertTrue(runCatching { manager.isolate(damage) }.isFailure)
            manager.isolate(manager.inventory().damaged.single { it.name == journal.name })
            assertTrue(manager.inventory().damaged.single { it.name == isolated.name }.isolated)
            assertEquals("damaged changed", isolated.readText())
            assertFalse(journal.exists())
            assertTrue(storage.overview().unlinked.none { it.path == wav.canonicalPath })
            assertTrue(runCatching { storage.cleanupUnlinked(UnlinkedAudio(wav.path, wav.length(), wav.lastModified())) }.isFailure)
            DocumentRepository(context, db).load(doc.id)
            assertTrue(repo.audioAssets(doc.id).isEmpty())
            val linkedFile = File(wav.parentFile, "linked.wav")
            created += linkedFile
            writeTestWav(linkedFile)
            repo.putAudio(SentenceAudio(id, doc.id, sentence.id, AudioKind.RECORDING, "local", "self",
                linkedFile.path, hash, 1000, System.currentTimeMillis()))
            assertTrue(storage.overview().items.single { it.audio.id == id }.protected)
            assertTrue(runCatching { storage.cleanupAudio(listOf(checkNotNull(repo.recordingById(id)))) }.isFailure)
            val warnings = backup.export(uri, ReaderSettings()).warnings
            assertTrue(warnings.any { id in it })
            val target = BackupManager(context, targetRepo)
            val prepared = target.prepare(uri)
            assertTrue(prepared.preview.warnings.containsAll(warnings))
            target.restore(prepared, acceptWarnings = true)
            assertEquals(doc.text, targetRepo.find(doc.id)?.text)
            val recovered = targetRepo.audioAssets(doc.id).single()
            assertNotNull(recovered.archivedAt)
            assertEquals(AudioFiles.sha256(linkedFile), AudioFiles.sha256(File(recovered.path)))
            assertEquals(wavHash, AudioFiles.sha256(wav))
        } finally {
            targetRepo.allAudioAssets().forEach { File(it.path).let { f -> f.delete(); f.parentFile?.delete() } }
            journal.delete(); isolated.delete(); zip.delete()
            created.forEach { it.delete() }; created.firstOrNull()?.parentFile?.delete()
            targetDb.close(); db.close()
        }
    }
}
