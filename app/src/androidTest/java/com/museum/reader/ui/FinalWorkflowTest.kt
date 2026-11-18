package com.museum.reader.ui

import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.MainActivity
import com.museum.reader.data.*
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.DocumentSource
import com.museum.reader.recording.PendingRecording
import com.museum.reader.recording.writeTestJournal
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FinalWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = ReaderDatabase.get(context)
    private val repo get() = DocumentRepository(context)
    private fun ready(): MuseumReaderViewModel {
        val vm = compose.runOnIdle { ViewModelProvider(compose.activity)[MuseumReaderViewModel::class.java] }
        compose.waitUntil(10000) { !vm.state.value.isBusy }
        return vm
    }

    @Test fun editedImportSurvivesRecreationAndSqlFailureThenRetryCreatesExactlyOneDocument() = runBlocking<Unit> {
        val vm = ready()
        val input = File(context.cacheDir, "preview-${UUID.randomUUID()}.txt").apply { writeText("待校对的一句。") }
        val title = "校对-" + UUID.randomUUID()
        val body = "用户校对的第一句。最后一句也已修正。"
        val trigger = "reject_final_import"
        val sql = db.openHelper.writableDatabase
        try {
            compose.runOnIdle { vm.importFile(Uri.fromFile(input)) }
            compose.onNodeWithText("导入前确认").let { node -> compose.waitUntil(10000) { vm.state.value.pendingImport != null }; node.assertExists() }
            val fields = compose.onAllNodes(hasSetTextAction())
            fields[0].performTextReplacement(title)
            fields[1].performTextReplacement(body)
            compose.waitUntil(10000) {
                vm.state.value.pendingImport?.let { it.title == title && it.text == body } == true
            }
            fields[1].assertTextContains(body)
            compose.activityRule.scenario.recreate()
            val resumed = ready()
            assertEquals(title, resumed.state.value.pendingImport?.title)
            assertEquals(body, resumed.state.value.pendingImport?.text)
            sql.beginTransaction()
            try {
                sql.execSQL("CREATE TRIGGER $trigger BEFORE INSERT ON documents WHEN NEW.title='$title' BEGIN SELECT RAISE(ABORT,'synthetic public test'); END")
                sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
            val confirm = hasText("确认导入") and isEnabled()
            compose.waitUntil(10000) { compose.onAllNodes(confirm).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(confirm).assertIsDisplayed().performClick()
            compose.waitUntil(10000) { resumed.state.value.importError != null && !resumed.state.value.importSaving }
            compose.onNodeWithText(checkNotNull(resumed.state.value.importError)).assertExists()
            assertEquals(title, resumed.state.value.pendingImport?.title)
            assertEquals(body, resumed.state.value.pendingImport?.text)
            assertTrue(repo.all().none { it.title == title })
            sql.beginTransaction()
            try { sql.execSQL("DROP TRIGGER $trigger"); sql.setTransactionSuccessful() } finally { sql.endTransaction() }
            compose.runOnIdle { resumed.confirmImport(); resumed.confirmImport() }
            compose.waitUntil(10000) { resumed.state.value.currentDocument?.title == title && resumed.state.value.pendingImport == null }
            assertEquals(body, repo.all().single { it.title == title }.text)
        } finally {
            sql.beginTransaction()
            try { sql.execSQL("DROP TRIGGER IF EXISTS $trigger"); sql.setTransactionSuccessful() } finally { sql.endTransaction() }
            repo.all().filter { it.title == title }.forEach { db.readerDao().purgeDocument(it.id) }
            input.delete()
        }
    }

    @Test fun interleavedEditsAndImmediateConfirmUseTheLatestTitleAndBody() = runBlocking<Unit> {
        val vm = ready()
        val title = "快速修订-" + UUID.randomUUID()
        val body = "最后一次校对的正文。"
        val input = File(context.cacheDir, "rapid-${UUID.randomUUID()}.txt").apply { writeText("原文。") }
        try {
            compose.runOnIdle { vm.importFile(Uri.fromFile(input)) }
            compose.waitUntil(10000) { vm.state.value.pendingImport != null }
            compose.runOnIdle {
                vm.editImport(text = "第一次校对。")
                vm.editImport(title = "临时名称")
                vm.editImport(text = body)
                vm.editImport(title = title)
                vm.confirmImport()
                vm.editImport(text = "保存开始后到达的编辑")
                vm.confirmImport()
            }
            compose.waitUntil(10000) { vm.state.value.currentDocument?.title == title }
            assertEquals(body, repo.all().single { it.title == title }.text)
        } finally {
            repo.all().filter { it.title == title }.forEach { db.readerDao().purgeDocument(it.id) }
            input.delete()
        }
    }

    @Test fun longImportPreviewComputesTheEditedCountsAndCanBeCancelled() {
        val vm = ready()
        val body = List(250) { "可重复定位的一段导览内容。".repeat(32) }.joinToString("\n")
        check(body.length >= 100_000)
        val input = File(context.cacheDir, "long-preview-${UUID.randomUUID()}.txt").apply { writeText(body) }
        val start = SystemClock.elapsedRealtime()
        try {
            compose.runOnIdle { vm.importFile(Uri.fromFile(input)) }
            compose.waitUntil(30000) {
                compose.onAllNodesWithText("${body.length} 字 · 250 段 · 8000 句").fetchSemanticsNodes().isNotEmpty()
            }
            val rendered = SystemClock.elapsedRealtime()
            val edited = body + "新增一句。"
            compose.runOnIdle { vm.editImport("长文已校对", edited) }
            compose.waitUntil(30000) {
                compose.onAllNodesWithText("${edited.length} 字 · 250 段 · 8001 句").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("确认导入").assertIsDisplayed().assertIsEnabled()
            val counted = SystemClock.elapsedRealtime()
            compose.onNodeWithText("取消").assertIsDisplayed().performClick()
            compose.waitUntil(10000) { vm.state.value.pendingImport == null }
            val label = InstrumentationRegistry.getArguments().getString("evidenceName", "preview")
            val directory = File(context.getExternalFilesDir(null), label!!).apply { mkdirs() }
            File(directory, "long-preview.json").writeText(org.json.JSONObject()
                .put("characters", body.length).put("sentences", 8000)
                .put("openToCountsMs", rendered - start).put("editToCountsMs", counted - rendered).toString())
        } finally { input.delete() }
    }

    @Test fun editingAMiddlePageSurvivesNavigationRotationAndImportsTheWholeText() = runBlocking<Unit> {
        val vm = ready()
        val body = "甲".repeat(2399) + "🧭" + "乙".repeat(2190) + "。" + "尾".repeat(600) + "。"
        val title = "page-" + UUID.randomUUID()
        val input = File(context.cacheDir, "$title.txt").apply { writeText(body) }
        try {
            compose.runOnIdle { vm.importFile(Uri.fromFile(input)) }
            compose.waitUntil(10000) { vm.state.value.pendingImport != null }
            val start = importPageEnd(body, 0)
            val end = importPageEnd(body, start)
            assertEquals(2399, start)
            val replacement = body.substring(start, end - 12) + "跨页校对。"
            val expected = body.replaceRange(start, end, replacement)
            fun next() = compose.onNodeWithText("下一页").performScrollTo().performClick()
            next()
            val field = compose.onAllNodes(hasSetTextAction())[1]
            field.assertTextContains(body.substring(start, end))
            field.performTextReplacement(replacement)
            assertEquals(expected, vm.state.value.pendingImport?.text)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.activityRule.scenario.recreate()
            ready()
            compose.onAllNodes(hasSetTextAction())[1].assertTextContains(replacement)
            next()
            compose.onAllNodes(hasSetTextAction())[1].assertTextContains(body.substring(end))
            compose.onNodeWithText("上一页").performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction())[1].assertTextContains(replacement)
            compose.onNodeWithText("确认导入").assertIsDisplayed().performClick()
            compose.waitUntil(10000) { vm.state.value.currentDocument?.title == title }
            assertEquals(expected, repo.all().single { it.title == title }.text)
        } finally {
            repo.all().filter { it.title == title }.forEach { db.readerDao().purgeDocument(it.id) }
            input.delete()
        }
    }

    @Test fun damagedRecordingCanBeCopiedAndQuarantinedOnlyAfterVisibleConfirmation() {
        val vm = ready()
        val id = UUID.randomUUID().toString()
        val raw = File(context.filesDir, "sentence-audio/recordings/$id/sample-$id.wav")
        val journal = File(context.filesDir, "recording-pending/$id.json")
        val isolated = File(journal.parentFile, journal.name + ".quarantined")
        val copy = File(context.cacheDir, "original-$id.wav")
        writeTestWav(raw)
        val hash = AudioFiles.sha256(raw)
        AudioFiles.writeAtomic(journal, "incomplete")
        try {
            compose.runOnIdle { vm.openStorage() }
            compose.waitUntil(10000) { !vm.state.value.isBusy && vm.state.value.damagedRecordings.any { it.name == journal.name } }
            val list = compose.onNode(hasScrollToIndexAction())
            list.performScrollToNode(hasText("隔离异常记录"))
            compose.onNodeWithText("另存原音频 · " + raw.name).assertExists()
            compose.runOnIdle { vm.stageAudioExport(raw.canonicalPath); vm.exportSelectedAudio(Uri.fromFile(copy)) }
            compose.waitUntil(10000) { !vm.state.value.isBusy && copy.isFile }
            assertEquals(hash, AudioFiles.sha256(copy))
            compose.onNodeWithText("隔离异常记录").performClick()
            compose.onNodeWithText("取消").performClick()
            assertTrue(journal.isFile)
            assertFalse(isolated.exists())
            compose.onNodeWithText("隔离异常记录").performClick()
            compose.onNodeWithText("确认隔离").performClick()
            compose.waitUntil(10000) { !vm.state.value.isBusy && vm.state.value.damagedRecordings.any { it.name == isolated.name && it.isolated } }
            list.performScrollToNode(hasText("已隔离的录音记录"))
            compose.onNodeWithText("已隔离的录音记录").assertExists()
            assertEquals("incomplete", isolated.readText())
            assertEquals(hash, AudioFiles.sha256(raw))
        } finally { journal.delete(); isolated.delete(); raw.delete(); raw.parentFile?.delete(); copy.delete() }
    }

    @Test fun recordedSentenceRemainsIdentifiableAfterMovingOrRetiringIt() = runBlocking<Unit> {
        val vm = ready()
        val doc = repo.create("原句定位", "浏览第一句。这里才是录音原句。", DocumentSource.TXT)
        val sentence = repo.load(doc.id)!!.content.sentences.last()
        val id = UUID.randomUUID().toString()
        val raw = File(context.filesDir, "sentence-audio/where-$id/raw.wav")
        writeTestWav(raw)
        val hash = AudioFiles.sha256(raw)
        val take = PendingRecording(id, doc.id, sentence.id, DocumentRepository.textHash(sentence.text), raw.path, 1000, true)
        val journal = writeTestJournal(context, take)
        fun open() {
            compose.runOnIdle { vm.openDocument(doc.id) }
            compose.waitUntil(10000) { vm.state.value.currentDocument?.id == doc.id && !vm.state.value.isBusy && vm.state.value.page == AppPage.READER }
            compose.runOnIdle { vm.openRecording() }
            compose.waitUntil(10000) { vm.state.value.recording.pending?.id == id && !vm.state.value.recording.processing }
        }
        try {
            open()
            assertEquals(1, vm.state.value.recording.currentIndex)
            compose.onNodeWithText(sentence.text).assertExists()
            compose.onNodeWithText("确认保存为本人录音").assertIsEnabled()
            compose.onNodeWithText("保留并返回").performClick()
            assertTrue(journal.isFile)
            repo.updateContent(doc.id, doc.title, "仅有这个替代句。")
            open()
            assertFalse(checkNotNull(vm.state.value.recording.pending).sentenceAvailable)
            compose.onNodeWithText(sentence.text).assertExists()
            compose.onNodeWithText("原句已修改或删除", substring = true).assertExists()
            compose.onNodeWithText("确认保存为本人录音").assertIsNotEnabled()
            compose.onNodeWithText("保留并返回").performClick()
            compose.runOnIdle { vm.openStorage() }
            compose.waitUntil(10000) { vm.state.value.pendingRecordings.any { it.id == id } && !vm.state.value.isBusy }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("确认保存为本人录音"))
            compose.onNodeWithText("确认保存为本人录音").assertIsNotEnabled()
            compose.onNodeWithText("另存本次原音频").assertExists()
            assertEquals(hash, AudioFiles.sha256(raw))
            assertTrue(repo.audioAssets(doc.id).isEmpty())
        } finally {
            journal.delete(); raw.delete(); raw.parentFile?.delete(); db.readerDao().purgeDocument(doc.id)
        }
    }
}
