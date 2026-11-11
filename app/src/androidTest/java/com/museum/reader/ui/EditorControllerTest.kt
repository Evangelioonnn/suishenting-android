package com.museum.reader.ui

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.data.DocumentRepository
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.DocumentSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorControllerTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun open(controller: EditorController, id: String) {
        val ready = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) { controller.open(id) { ready.complete(Unit) } }
        withTimeout(4000) { ready.await() }
    }
    @Test fun durableDraftRestoresExactTitleTextAndSelectionWithoutChangingFormalCopy() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val doc = repo.create("正式标题", "正式正文。", DocumentSource.TXT)
            val editor = EditorController(repo, scope) {}
            open(editor, doc.id)
            withContext(Dispatchers.Main) {
                editor.change("草稿标题", "连续输入的草稿。")
                editor.position(3, 6, 120)
            }
            withTimeout(2500) { editor.state.first { it.persistedRevision == it.revision && it.dirty } }
            assertEquals("正式正文。", repo.find(doc.id)!!.text)
            val second = EditorController(repo, scope) {}
            open(second, doc.id)
            assertNotNull(second.state.value.pendingDraft)
            withContext(Dispatchers.Main) { second.chooseDraft(true) }
            assertEquals("草稿标题", second.state.value.title)
            assertEquals("连续输入的草稿。", second.state.value.text)
            assertEquals(3, second.state.value.selectionStart)
            assertEquals(6, second.state.value.selectionEnd)
            val saved = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) { second.save { saved.complete(Unit) } }
            withTimeout(4000) { saved.await() }
            assertEquals("连续输入的草稿。", repo.find(doc.id)!!.text)
            assertNull(repo.draft(doc.id))
            assertEquals(1, second.state.value.versions.size)
            withContext(Dispatchers.Main) { second.save() }
            withTimeout(4000) { second.state.first { !it.saving } }
            assertEquals(1, repo.versions(doc.id).size)
        } finally { scope.cancel(); db.close() }
    }

    @Test fun typingDuringSaveSurvivesAndDiscardCannotBeUndoneByLateAutosave() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val doc = repo.create("并发保存", "原文。", DocumentSource.TXT)
            val editor = EditorController(repo, scope) {}
            open(editor, doc.id)
            val locked = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val held = launch(Dispatchers.IO) { db.withTransaction { locked.complete(Unit); release.await() } }
            locked.await()
            withContext(Dispatchers.Main) {
                editor.change("第一次", "提交时的正文。")
                editor.save()
                editor.save() // A duplicate press cannot queue another formal save.
                editor.change("第二次", "保存期间继续输入的正文。")
            }
            release.complete(Unit); held.join()
            withTimeout(4000) { editor.state.first { !it.saving && it.persistedRevision == it.revision } }
            assertEquals("提交时的正文。", repo.find(doc.id)!!.text)
            assertEquals("保存期间继续输入的正文。", editor.state.value.text)
            assertEquals("保存期间继续输入的正文。", repo.draft(doc.id)!!.text)
            val discarded = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) { editor.discard { discarded.complete(Unit) } }
            withTimeout(4000) { discarded.await() }
            delay(900)
            assertNull(repo.draft(doc.id))
            assertEquals("提交时的正文。", repo.find(doc.id)!!.text)
        } finally { scope.cancel(); db.close() }
    }

    @Test fun databaseFailureKeepsInputAndFormalCopyUntilSaveAndRestoreAreRetried() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val doc = repo.create("原始标题", "原始正文。", DocumentSource.TXT)
            repo.updateContent(doc.id, "正式标题", "当前正式正文。")
            val originalVersion = repo.versions(doc.id).single()
            val completed = java.util.concurrent.atomic.AtomicInteger()
            val editor = EditorController(repo, scope) { completed.incrementAndGet() }
            open(editor, doc.id)
            withContext(Dispatchers.Main) { editor.change("修改标题", "必须保留的输入。") }
            withTimeout(4000) { editor.state.first { it.persistedRevision == it.revision && it.dirty } }
            val sql = db.openHelper.writableDatabase
            fun failWrites() = sql.execSQL("CREATE TRIGGER fail_editor_write BEFORE UPDATE ON documents BEGIN SELECT RAISE(ABORT, 'private-sql-details'); END")
            fun allowWrites() = sql.execSQL("DROP TRIGGER fail_editor_write")
            failWrites()
            withContext(Dispatchers.Main) { editor.save() }
            val failed = withTimeout(4000) { editor.state.first { !it.saving && it.error != null } }
            val saveMessage = checkNotNull(failed.error)
            assertTrue(saveMessage.contains("保存失败"))
            assertTrue(saveMessage.contains("再点保存"))
            assertFalse(saveMessage.contains("private-sql-details"))
            assertFalse(saveMessage.contains("SQLITE"))
            assertTrue(failed.dirty)
            assertEquals("必须保留的输入。", failed.text)
            assertEquals("必须保留的输入。", repo.draft(doc.id)!!.text)
            assertEquals("当前正式正文。", repo.find(doc.id)!!.text)
            assertEquals(1, repo.versions(doc.id).size)
            assertEquals(0, completed.get())
            allowWrites()
            withContext(Dispatchers.Main) { editor.save() }
            withTimeout(4000) { editor.state.first { !it.saving && !it.dirty && it.error == null } }
            assertEquals("必须保留的输入。", repo.find(doc.id)!!.text)
            withTimeout(4000) { while (completed.get() != 1 || repo.draft(doc.id) != null) delay(20) }

            failWrites()
            withContext(Dispatchers.Main) { editor.restoreVersion(originalVersion.id) }
            val restoreFailed = withTimeout(4000) { editor.state.first { !it.saving && it.error != null } }
            val restoreMessage = checkNotNull(restoreFailed.error)
            assertTrue(restoreMessage.contains("恢复失败"))
            assertTrue(restoreMessage.contains("重新选择版本"))
            assertFalse(restoreMessage.contains("private-sql-details"))
            assertEquals("必须保留的输入。", repo.find(doc.id)!!.text)
            assertEquals(2, repo.versions(doc.id).size)
            assertEquals(1, completed.get())
            allowWrites()
            withContext(Dispatchers.Main) { editor.restoreVersion(originalVersion.id) }
            withTimeout(4000) { editor.state.first { !it.saving && it.error == null && it.title == "原始标题" } }
            assertEquals("原始正文。", repo.find(doc.id)!!.text)
            assertEquals(2, completed.get())
        } finally { scope.cancel(); db.close() }
    }

    @Test fun unreadableDatabaseShowsRecoveryGuidanceWithoutTechnicalDetails() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        val doc = repo.create("打开检查", "保留的正文。", DocumentSource.TXT)
        db.close()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val editor = EditorController(repo, scope) {}
            var ready = false
            val failureReported = CompletableDeferred<String>()
            withContext(Dispatchers.Main) {
                editor.open(doc.id, onFailure = { failureReported.complete(it) }) { ready = true }
            }
            val failed = withTimeout(4000) { editor.state.first { it.error != null } }
            val message = checkNotNull(failed.error)
            assertTrue(message.contains("无法打开文稿"))
            assertTrue(message.contains("返回后重试"))
            assertFalse(message.contains("database", ignoreCase = true))
            assertEquals(message, withTimeout(4000) { failureReported.await() })
            assertFalse(ready)
            assertFalse(failed.saving)
        } finally { scope.cancel() }
    }
}
