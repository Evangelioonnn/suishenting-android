package com.museum.reader.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.data.db.DocumentVersionEntity
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.*
import com.museum.reader.ui.EditorController
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConcurrentDataProtectionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun open(editor: EditorController, id: String) {
        val ready = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) { editor.open(id) { ready.complete(Unit) } }
        withTimeout(4000) { ready.await() }
    }

    @Test fun typingDuringHistoricalRestoreRemainsInTheEditorAndDurableDraft() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val release = CompletableDeferred<Unit>(); var held: Job? = null
        try {
            val doc = repo.create("历史原名", "历史原文。", DocumentSource.TXT)
            repo.updateContent(doc.id, "当前名称", "当前正文。")
            val version = repo.versions(doc.id).single()
            val editor = EditorController(repo, scope) {}; open(editor, doc.id)
            val locked = CompletableDeferred<Unit>()
            held = launch(Dispatchers.IO) { db.withTransaction { locked.complete(Unit); release.await() } }; locked.await()
            withContext(Dispatchers.Main) {
                editor.restoreVersion(version.id)
                editor.change("恢复期间的新标题", "恢复期间继续输入，必须保留。")
                editor.position(2, 6, 80)
            }
            release.complete(Unit); held.join()
            withTimeout(4000) { editor.state.first { !it.saving } }
            assertEquals("历史原文。", repo.find(doc.id)!!.text)
            assertEquals("恢复期间继续输入，必须保留。", editor.state.value.text)
            assertEquals("恢复期间的新标题", editor.state.value.title)
            assertEquals(2, editor.state.value.selectionStart); assertEquals(6, editor.state.value.selectionEnd)
            withTimeout(2500) { editor.state.first { it.dirty && it.persistedRevision == it.revision } }
            assertEquals(editor.state.value.text, repo.draft(doc.id)!!.text)
            assertEquals(editor.state.value.title, repo.draft(doc.id)!!.title)
        } finally { release.complete(Unit); held?.join(); scope.cancel(); db.close() }
    }

    @Test fun repeatedDiscardAndSaveWhileDiscardingCannotCommitTheDiscardedBuffer() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val release = CompletableDeferred<Unit>(); var held: Job? = null
        try {
            val doc = repo.create("正式名称", "正式正文。", DocumentSource.TXT)
            val editor = EditorController(repo, scope) {}; open(editor, doc.id)
            val locked = CompletableDeferred<Unit>(); val callbacks = AtomicInteger()
            held = launch(Dispatchers.IO) { db.withTransaction { locked.complete(Unit); release.await() } }; locked.await()
            withContext(Dispatchers.Main) {
                editor.change("要放弃的标题", "用户已经决定放弃的内容。")
                editor.discard { callbacks.incrementAndGet() }
                editor.discard { callbacks.incrementAndGet() }
                editor.save()
            }
            release.complete(Unit); held.join()
            withTimeout(4000) { while (callbacks.get() == 0) delay(20) }
            delay(900)
            assertEquals(1, callbacks.get())
            assertEquals("正式正文。", repo.find(doc.id)!!.text)
            assertNull(repo.draft(doc.id)); assertNull(editor.state.value.document)
        } finally { release.complete(Unit); held?.join(); scope.cancel(); db.close() }
    }

    @Test fun historyAddedBeforeCleanupCommitProtectsTheAudioReferenceAndFile() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        val dir = File(context.filesDir, "sentence-audio/concurrent-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val doc = repo.create("清理并发保护", "历史中的旧句。", DocumentSource.TXT)
            val sentence = repo.load(doc.id)!!.content.sentences.single()
            val file = File(dir, "old.wav").also(::writeTestWav)
            val audio = SentenceAudio(UUID.randomUUID().toString(), doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", file.path, DocumentRepository.textHash(sentence.text), 1000)
            repo.putAudio(audio); repo.updateContent(doc.id, doc.title, "当前正文。")
            db.readerDao().trimVersions(doc.id, 0)
            val snapshot = JSONArray().put(JSONObject().put("id", sentence.id).put("text", sentence.text)).toString()
            val historical = DocumentVersionEntity(UUID.randomUUID().toString(), doc.id, doc.title, sentence.text, 1L, snapshot)
            val manager = StorageMaintenance(context, repo, db) { if (it == "before_commit") runBlocking { db.readerDao().putVersion(historical) } }
            assertFalse(manager.overview().items.single().protected)
            assertTrue("New historical protection must be rechecked at the commit boundary", runCatching {
                manager.cleanupAudio(listOf(audio))
            }.isFailure)
            assertEquals(listOf(audio), repo.allAudioAssets()); assertTrue(file.isFile)
            assertEquals(listOf(historical), db.readerDao().versions(doc.id))
        } finally { dir.listFiles().orEmpty().forEach { it.delete() }; dir.delete(); StorageMaintenance(context, repo, db).recover(); db.close() }
    }

    @Test fun unlinkedFileChangedBeforeCleanupCommitIsNotDeleted() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build(); val repo = DocumentRepository(context, db)
        val dir = File(context.filesDir, "sentence-audio/unlinked-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val file = File(dir, "changed.wav").also(::writeTestWav)
            val manager = StorageMaintenance(context, repo, db) { if (it == "before_commit") file.appendBytes(byteArrayOf(1, 2, 3)) }
            val expected = UnlinkedAudio(file.path, file.length(), file.lastModified())
            assertTrue(runCatching { manager.cleanupUnlinked(expected) }.isFailure)
            assertTrue(file.isFile); assertEquals(expected.bytes + 3, file.length())
        } finally { dir.listFiles().orEmpty().forEach { it.delete() }; dir.delete(); StorageMaintenance(context, repo, db).recover(); db.close() }
    }

    @Test fun newPendingRecordingBeforeCleanupCommitRetainsItsAudioReference() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build(); val repo = DocumentRepository(context, db)
        val dir = File(context.filesDir, "sentence-audio/pending-${UUID.randomUUID()}").apply { mkdirs() }
        val journal = File(context.filesDir, "recording-pending/${UUID.randomUUID()}.json")
        try {
            val doc = repo.create("待确认并发保护", "正文。", DocumentSource.TXT)
            val sentence = repo.load(doc.id)!!.content.sentences.single()
            val file = File(dir, "pending.wav").also(::writeTestWav)
            val audio = SentenceAudio(UUID.randomUUID().toString(), doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", file.path, DocumentRepository.textHash(sentence.text), 1000, archivedAt = 1)
            repo.putAudio(audio)
            val manager = StorageMaintenance(context, repo, db) { if (it == "before_commit") {
                journal.parentFile!!.mkdirs(); journal.writeText(JSONObject().put("path", file.path).toString())
            } }
            assertTrue(runCatching { manager.cleanupAudio(listOf(audio)) }.isFailure)
            assertEquals(listOf(audio), repo.allAudioAssets()); assertTrue(file.isFile)
        } finally { journal.delete(); dir.listFiles().orEmpty().forEach { it.delete() }; dir.delete(); StorageMaintenance(context, repo, db).recover(); db.close() }
    }

    @Test fun concurrentBookmarkTogglesAreAtomicAndFollowTheNumberOfAcceptedActions() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build(); val repo = DocumentRepository(context, db)
        try {
            val doc = repo.create("重点并发操作", "同一句重点。", DocumentSource.TXT)
            val sentence = repo.load(doc.id)!!.content.sentences.single()
            val start = CompletableDeferred<Unit>()
            val requests = (1..20).map { async(Dispatchers.IO) { start.await(); runCatching { repo.toggleBookmark(doc.id, sentence.id) } } }
            start.complete(Unit)
            val results = withTimeout(8000) { requests.awaitAll() }
            assertTrue("Every accepted toggle must complete without a uniqueness error", results.all { it.isSuccess })
            assertEquals(10, results.count { it.getOrNull() == true })
            assertEquals(10, results.count { it.getOrNull() == false })
            assertTrue("An even number of toggles must restore the initial unmarked state", repo.bookmarks(doc.id).isEmpty())
        } finally { db.close() }
    }
}
