package com.museum.reader.ui

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.data.DocumentRepository
import com.museum.reader.data.db.EditorDraftEntity
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.DocumentSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@RunWith(AndroidJUnit4::class)
class EditorFailureBoundaryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun open(editor: EditorController, id: String) {
        val ready = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) { editor.open(id) { ready.complete(Unit) } }
        withTimeout(4000) { ready.await() }
    }

    @Test fun failedDraftChoiceRemainsRetryableWithoutUncaughtException() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, error -> uncaught += error })
        try {
            val doc = repo.create("正式标题", "必须保留的正式内容。", DocumentSource.TXT)
            val draft = EditorDraftEntity(doc.id, "未提交草稿", "必须保留的未提交输入。", 2, 5, System.currentTimeMillis())
            repo.saveDraft(draft)
            val editor = EditorController(repo, scope) {}
            val ready = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) { editor.open(doc.id) { ready.complete(Unit) } }
            withTimeout(4000) { ready.await() }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER fail_draft_delete BEFORE DELETE ON editor_drafts BEGIN SELECT RAISE(ABORT, 'private-draft-sql'); END")
            withContext(Dispatchers.Main) { editor.chooseDraft(false); editor.chooseDraft(false) }
            withTimeout(4000) {
                while (editor.state.value.error == null && uncaught.isEmpty()) delay(20)
            }
            assertTrue("Draft deletion must have a controlled failure", uncaught.isEmpty())
            assertNotNull("Failure must retain the user's choice and draft", editor.state.value.pendingDraft)
            assertTrue(editor.state.value.error.orEmpty().contains("重试"))
            assertFalse(editor.state.value.error.orEmpty().contains("private-draft-sql"))
            assertEquals(draft, repo.draft(doc.id))
            assertEquals("必须保留的正式内容。", repo.find(doc.id)!!.text)
            sql.execSQL("DROP TRIGGER fail_draft_delete")
            withContext(Dispatchers.Main) { editor.chooseDraft(false) }
            withTimeout(4000) { editor.state.first { it.pendingDraft == null && it.error == null } }
            assertNull(repo.draft(doc.id))
            assertEquals("必须保留的正式内容。", editor.state.value.text)
        } finally { scope.cancel(); db.close() }
    }

    @Test fun formalCommitWithFailedDraftCleanupIsReportedAsCommittedAndCanBeRetried() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val doc = repo.create("提交前", "提交前正文。", DocumentSource.TXT)
            val editor = EditorController(repo, scope) {}
            open(editor, doc.id)
            withContext(Dispatchers.Main) { editor.change("提交后", "数据库已提交的正文。") }
            withTimeout(4000) { editor.state.first { it.dirty && it.persistedRevision == it.revision } }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER fail_draft_cleanup BEFORE DELETE ON editor_drafts BEGIN SELECT RAISE(ABORT, 'private-cleanup-sql'); END")
            withContext(Dispatchers.Main) { editor.save() }
            val failed = withTimeout(4000) { editor.state.first { !it.saving && it.error != null } }
            assertTrue(failed.error.orEmpty().contains("正式文稿已保存"))
            assertFalse(failed.error.orEmpty().contains("private-cleanup-sql"))
            assertFalse(failed.dirty)
            assertEquals("数据库已提交的正文。", repo.find(doc.id)!!.text)
            assertNotNull(repo.draft(doc.id))
            val history = repo.versions(doc.id).size
            sql.execSQL("DROP TRIGGER fail_draft_cleanup")
            withContext(Dispatchers.Main) { editor.save() }
            withTimeout(4000) { editor.state.first { !it.saving && it.error == null } }
            assertNull(repo.draft(doc.id))
            assertEquals(history, repo.versions(doc.id).size)
        } finally { scope.cancel(); db.close() }
    }

    @Test fun lateSaveFailureCannotReplaceAnotherDocumentOrAReopenedDraft() = runBlocking<Unit> {
        for (sameDocument in listOf(false, true)) {
            val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
            val repo = DocumentRepository(context, db)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val release = CompletableDeferred<Unit>()
            try {
                val a = repo.create("A", "原A正文。", DocumentSource.TXT)
                val b = if (sameDocument) a else repo.create("B", "B不受影响。", DocumentSource.TXT)
                val entered = CompletableDeferred<Unit>()
                val finished = CompletableDeferred<Unit>()
                val editor = EditorController(repo, scope) {
                    entered.complete(Unit)
                    try { release.await(); error("private-late-save-error") }
                    finally { finished.complete(Unit) }
                }
                open(editor, a.id)
                var exitCalled = false
                withContext(Dispatchers.Main) { editor.change("已提交A", "A提交内容。"); editor.save { exitCalled = true } }
                withTimeout(4000) { entered.await() }
                val newDraft = EditorDraftEntity(b.id, "较新的未提交内容", "新页面的草稿。", 1, 3, System.currentTimeMillis())
                repo.saveDraft(newDraft)
                open(editor, b.id)
                val installed = editor.state.value
                release.complete(Unit)
                withTimeout(4000) { finished.await() }
                withContext(Dispatchers.Main) { yield() }
                assertEquals(installed, editor.state.value)
                assertNull(editor.state.value.error)
                assertFalse(exitCalled)
                assertEquals(newDraft, repo.draft(b.id))
            } finally { release.complete(Unit); scope.cancel(); db.close() }
        }
    }

    @Test fun inputDuringPostCommitRefreshIsNotClosedBySaveAndExit() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val release = CompletableDeferred<Unit>()
        try {
            val doc = repo.create("原文", "原正文。", DocumentSource.TXT)
            val entered = CompletableDeferred<Unit>()
            val editor = EditorController(repo, scope) { entered.complete(Unit); release.await() }
            open(editor, doc.id)
            var exited = false
            withContext(Dispatchers.Main) { editor.change("提交", "已提交内容。"); editor.save { exited = true } }
            withTimeout(4000) { entered.await() }
            assertTrue(editor.state.value.saving)
            withContext(Dispatchers.Main) { editor.change("继续输入", "后处理期间的新输入。"); editor.save() }
            release.complete(Unit)
            withTimeout(4000) { editor.state.first { !it.saving && it.dirty && it.persistedRevision == it.revision } }
            assertFalse(exited)
            assertEquals("已提交内容。", repo.find(doc.id)!!.text)
            assertEquals("后处理期间的新输入。", repo.draft(doc.id)!!.text)
        } finally { release.complete(Unit); scope.cancel(); db.close() }
    }

    @Test fun reopeningDuringDraftDeletionCannotClaimARestoredDraftIsDurableAfterLateDelete() = runBlocking<Unit> {
        val name = "draft-choice-race-${java.util.UUID.randomUUID()}.db"
        val armed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val db = Room.databaseBuilder(context, ReaderDatabase::class.java, name)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback({ query, _ ->
                if (query.contains("DELETE FROM editor_drafts", true) && armed.compareAndSet(true, false)) {
                    entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                }
            }, java.util.concurrent.Executor { it.run() }).build()
        val repo = DocumentRepository(context, db)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val doc = repo.create("正式内容", "不能被草稿覆盖的正式正文。", DocumentSource.TXT)
            val draft = EditorDraftEntity(doc.id, "可恢复草稿", "需要重新确认持久化的输入。", 2, 5, System.currentTimeMillis())
            repo.saveDraft(draft)
            val editor = EditorController(repo, scope) {}
            open(editor, doc.id)
            armed.set(true)
            withContext(Dispatchers.Main) { editor.chooseDraft(false) }
            check(entered.await(4, TimeUnit.SECONDS)) { "Actual SQLite delete was not gated" }
            val reopened = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) { editor.open(doc.id) { reopened.complete(Unit) } }
            delay(300)
            val serializedRead = !reopened.isCompleted
            if (serializedRead) {
                // Room may serialize the multi-query manuscript read behind this write.
                // Do not turn that protection into an artificial open deadline failure.
                release.countDown()
                withTimeout(4000) { reopened.await() }
                assertNull(editor.state.value.pendingDraft)
                assertNull(repo.draft(doc.id))
                assertFalse(editor.state.value.dirty)
            } else {
                assertEquals(draft, editor.state.value.pendingDraft)
                withContext(Dispatchers.Main) { editor.chooseDraft(true) }
                assertEquals(draft.text, editor.state.value.text)
                release.countDown()
            }
            val writes = EditorController::class.java.getDeclaredField("writes").apply { isAccessible = true }.get(editor) as Mutex
            writes.withLock { }
            if (!serializedRead) {
                val actual = repo.draft(doc.id)
                assertFalse("A late delete must not remove a draft while the editor says it is saved",
                    actual == null && editor.state.value.persistedRevision == editor.state.value.revision)
                withContext(Dispatchers.Main) { editor.flush() }
                withTimeout(4000) { editor.state.first { it.persistedRevision == it.revision } }
                assertEquals(draft.text, repo.draft(doc.id)!!.text)
            }
            assertEquals(doc.text, repo.find(doc.id)!!.text)
            val run = InstrumentationRegistry.getArguments().getString("evidenceName", "draft-reopen-delete")!!
            val out = java.io.File(context.getExternalFilesDir(null), run).apply { mkdirs() }
            java.io.File(out, "draft-reopen-delete.json").writeText(org.json.JSONObject()
                .put("passed", true).put("actualSqlDeleteGate", true).put("reopenSerializedBehindDelete", serializedRead)
                .put("concurrentRestoreBranchExercised", !serializedRead).put("formalUnchanged", true).toString(2))
        } finally { release.countDown(); scope.cancel(); db.close(); context.deleteDatabase(name) }
    }
}
