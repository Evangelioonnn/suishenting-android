package com.museum.reader.ui

import android.graphics.Rect
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.MainActivity
import com.museum.reader.cloud.*
import com.museum.reader.data.*
import com.museum.reader.data.db.EditorDraftEntity
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.*
import com.museum.reader.recording.PendingRecording
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** These tests use real application pages, OS touches and actual SQL/IO failures on synthetic data. */
class FailureRecoveryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repo get() = DocumentRepository(context)
    private val sql get() = ReaderDatabase.get(context).openHelper.writableDatabase
    private lateinit var vm: MuseumReaderViewModel
    private val runName get() = InstrumentationRegistry.getArguments().getString("evidenceName", "failure-ui")!!
    private val output get() = File(context.getExternalFilesDir(null), runName).apply { mkdirs() }

    private fun ready() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[MuseumReaderViewModel::class.java] }
        compose.waitUntil(8000) { !vm.state.value.isBusy }
        compose.runOnIdle { vm.dismissFailure() }
    }

    private fun writeSql(statement: String) {
        // Reserve a writer explicitly for test trigger DDL on all supported Android versions.
        sql.beginTransaction()
        try { sql.execSQL(statement); sql.setTransactionSuccessful() }
        finally { sql.endTransaction() }
    }

    private fun visibleRoots(): List<AccessibilityNodeInfo> = instrumentation.uiAutomation.windows
        .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
        .sortedByDescending { if (it.isFocused) 2 else if (it.isActive) 1 else 0 }
        .mapNotNull { it.root }
        .ifEmpty { listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow) }

    private fun survey(name: String) {
        val nodes = org.json.JSONArray()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || nodes.length() >= 512) return
            val bounds = Rect().also(node::getBoundsInScreen)
            nodes.put(JSONObject().put("text", if (node.isPassword) "<password>" else node.text?.toString())
                .put("description", node.contentDescription?.toString()).put("bounds", bounds.toShortString())
                .put("visible", node.isVisibleToUser).put("clickable", node.isClickable).put("enabled", node.isEnabled))
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visibleRoots().forEach(::visit)
        File(output, "$name-nodes.json").writeText(nodes.toString(2))
    }

    private fun tap(label: String, contains: Boolean = false) {
        if (compose.onAllNodesWithText(label, substring = contains).fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label, substring = contains))
        }
        try { compose.onNodeWithText(label, substring = contains).performScrollTo() } catch (_: AssertionError) { }
        fun target(node: AccessibilityNodeInfo?): Rect? {
            if (node == null) return null
            node.refresh()
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val matches = if (contains) text.contains(label) || description.contains(label) else text == label || description == label
            if (matches && node.isVisibleToUser && node.isEnabled) {
                // A visible native/virtual label can be touchable even when the older OS
                // does not export isClickable. The actual touch and following state prove it.
                val own = Rect().also(node::getBoundsInScreen)
                if (!own.isEmpty) return own
                var clickable = node
                repeat(5) {
                    if (clickable?.isClickable == true) return Rect().also { clickable?.getBoundsInScreen(it) }
                    clickable = clickable?.parent
                }
            }
            repeat(node.childCount) { target(node.getChild(it))?.let { bounds -> return bounds } }
            return null
        }
        val automation = instrumentation.uiAutomation
        var bounds: Rect? = null
        var previous: Rect? = null
        var stableSince = 0L
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            val found = visibleRoots().firstNotNullOfOrNull { target(it) }
            if (found != null && !found.isEmpty && found.left >= 0 && found.top >= 0) {
                if (found == previous) {
                    if (SystemClock.uptimeMillis() - stableSince >= 300) { bounds = found; break }
                } else { previous = found; stableSince = SystemClock.uptimeMillis() }
            } else { previous = null }
            SystemClock.sleep(40)
        }
        if (bounds == null) {
            val name = "unreachable-action-${UUID.randomUUID()}"
            survey(name); capture(name, passed = false)
        }
        val actual = checkNotNull(bounds) { "No stable visible OS action: $label" }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, actual.exactCenterX(), actual.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String, passed: Boolean = true) {
        compose.waitForIdle()
        fun visibleText(node: AccessibilityNodeInfo?): String {
            if (node == null || !node.refresh()) return ""
            val result = StringBuilder()
            if (node.isVisibleToUser) result.append(node.text?.toString().orEmpty()).append('\n')
            repeat(node.childCount) { result.append(visibleText(node.getChild(it))) }
            return result.toString()
        }
        try { if (passed) compose.waitUntil(8000) {
            val text = visibleRoots().joinToString("\n") { visibleText(it) }
            val failure = vm.state.value.failure
            val editorError = vm.editor.state.value.error?.takeIf { vm.state.value.page == AppPage.EDITOR }
            text.isNotBlank() && when {
                failure != null -> text.contains(failure.operation.title) && text.contains(failure.message)
                editorError != null -> text.contains(editorError)
                !vm.state.value.isBusy -> !text.contains(vm.state.value.busyLabel) &&
                    (vm.state.value.recording.processing || !text.contains("正在校验并整理录音"))
                else -> text.contains(vm.state.value.busyLabel)
            }
        } } catch (failure: Exception) {
            survey("$name-window-not-ready")
            capture("$name-window-not-ready", passed = false)
            throw failure
        }
        val frames = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val decor = compose.activity.window.decorView
            decor.postInvalidateOnAnimation()
            decor.postOnAnimation { decor.postOnAnimation { frames.countDown() } }
        }
        check(frames.await(3, TimeUnit.SECONDS)) { "The UI did not produce stable frames" }
        SystemClock.sleep(350)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(output, "$name.json").writeText(JSONObject().put("stage", name).put("passed", passed)
            .put("actualOsTouches", true).put("realDevice", false).put("realCloudCalls", false)
            .put("syntheticAudio", true).put("busy", vm.state.value.isBusy)
            .put("densityDpi", context.resources.displayMetrics.densityDpi)
            .put("fontScale", context.resources.configuration.fontScale)
            .put("orientation", context.resources.configuration.orientation).toString(2))
    }

    private suspend fun cleanup(id: String) {
        compose.runOnIdle { vm.dismissFailure(); vm.editor.discard {}; vm.goHome() }
        compose.waitUntil(6000) { vm.editor.state.value.document == null }
        ReaderDatabase.get(context).readerDao().purgeDocument(id)
    }

    @Test fun draftDeleteFailureKeepsTheChoiceVisibleAndActualRetrySucceeds() = runBlocking<Unit> {
        ready()
        val doc = repo.create("草稿失败保护", "正式正文不变。", DocumentSource.TXT)
        val draft = EditorDraftEntity(doc.id, "未提交标题", "需要保护的输入。", 1, 4, System.currentTimeMillis())
        try {
            repo.saveDraft(draft)
            compose.runOnIdle { vm.openEditor(doc) }
            compose.waitUntil(6000) { vm.editor.state.value.pendingDraft != null }
            writeSql("CREATE TRIGGER l03_draft_failure BEFORE DELETE ON editor_drafts WHEN OLD.documentId='${doc.id}' BEGIN SELECT RAISE(ABORT, 'private-ui-draft'); END")
            tap("使用正式版本")
            compose.waitUntil(6000) { vm.editor.state.value.error != null && !vm.editor.state.value.choosingDraft }
            compose.onNode(isDialog()).assertExists()
            assertEquals(draft, repo.draft(doc.id))
            assertEquals("正式正文不变。", repo.find(doc.id)!!.text)
            capture("draft-delete-failed")
            writeSql("DROP TRIGGER l03_draft_failure")
            tap("使用正式版本")
            compose.waitUntil(6000) { vm.editor.state.value.pendingDraft == null && vm.editor.state.value.error == null }
            assertNull(repo.draft(doc.id))
            capture("draft-delete-retried")
        } finally { writeSql("DROP TRIGGER IF EXISTS l03_draft_failure"); cleanup(doc.id) }
    }

    private fun selectHistory(title: String) {
        tap("更多"); tap("版本历史"); tap("$title ·", contains = true)
    }

    @Test fun actualAutosaveAndFormalSaveFailuresKeepInputVisibleAndBothRetriesSucceed() = runBlocking<Unit> {
        ready()
        val doc = repo.create("输入保存保护", "正式内容始终保留。", DocumentSource.TXT)
        try {
            compose.runOnIdle { vm.openEditor(doc) }
            compose.waitUntil(8000) { vm.state.value.page == AppPage.EDITOR }
            writeSql("CREATE TRIGGER l03_autosave_failure BEFORE INSERT ON editor_drafts WHEN NEW.documentId='${doc.id}' BEGIN SELECT RAISE(ABORT, 'private-autosave'); END")
            tap("讲解词正文")
            instrumentation.sendStringSync("x")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(8000) { vm.editor.state.value.error?.contains("草稿保存失败") == true }
            val input = vm.editor.state.value.text
            assertTrue(input.contains("x"))
            assertNull(repo.draft(doc.id))
            assertEquals(doc.text, repo.find(doc.id)!!.text)
            assertTrue(vm.editor.state.value.persistedRevision < vm.editor.state.value.revision)
            capture("autosave-failed-input-preserved")
            tap("返回编辑")
            writeSql("DROP TRIGGER l03_autosave_failure")
            compose.waitUntil(8000) { vm.editor.state.value.error == null && vm.editor.state.value.persistedRevision == vm.editor.state.value.revision }
            assertEquals(input, repo.draft(doc.id)!!.text)
            capture("autosave-retried")

            writeSql("CREATE TRIGGER l03_formal_failure BEFORE UPDATE ON documents WHEN OLD.id='${doc.id}' BEGIN SELECT RAISE(ABORT, 'private-formal-save'); END")
            tap("保存")
            compose.waitUntil(8000) { !vm.editor.state.value.saving && vm.editor.state.value.error?.contains("保存失败") == true }
            assertEquals(input, vm.editor.state.value.text)
            assertEquals(input, repo.draft(doc.id)!!.text)
            assertEquals(doc.text, repo.find(doc.id)!!.text)
            capture("formal-save-failed-input-preserved")
            tap("返回编辑")
            writeSql("DROP TRIGGER l03_formal_failure")
            tap("保存")
            compose.waitUntil(8000) { !vm.editor.state.value.saving && !vm.editor.state.value.dirty && vm.editor.state.value.error == null }
            assertEquals(input, repo.find(doc.id)!!.text)
            assertNull(repo.draft(doc.id))
            capture("formal-save-retried")
        } finally {
            writeSql("DROP TRIGGER IF EXISTS l03_autosave_failure")
            writeSql("DROP TRIGGER IF EXISTS l03_formal_failure")
            cleanup(doc.id)
        }
    }

    @Test fun historyRestoreFailureStaysActionableAndRetryPreservesTheCurrentVersion() = runBlocking<Unit> {
        ready()
        val doc = repo.create("可恢复旧版本", "原始版本正文。", DocumentSource.TXT)
        repo.updateContent(doc.id, "当前正式版", "当前版本也必须可找回。")
        try {
            compose.runOnIdle { vm.openEditor(doc) }
            compose.waitUntil(6000) { vm.state.value.page == AppPage.EDITOR && vm.editor.state.value.versions.isNotEmpty() }
            writeSql("CREATE TRIGGER l03_history_failure BEFORE UPDATE ON documents WHEN OLD.id='${doc.id}' BEGIN SELECT RAISE(ABORT, 'private-ui-history'); END")
            selectHistory("可恢复旧版本")
            compose.waitUntil(6000) { vm.editor.state.value.error != null && !vm.editor.state.value.saving }
            assertTrue(vm.editor.state.value.error.orEmpty().contains("恢复失败"))
            assertFalse(vm.editor.state.value.error.orEmpty().contains("private-ui-history"))
            assertEquals("当前版本也必须可找回。", repo.find(doc.id)!!.text)
            capture("history-restore-failed")
            writeSql("DROP TRIGGER l03_history_failure")
            tap("返回编辑")
            selectHistory("可恢复旧版本")
            compose.waitUntil(6000) { !vm.editor.state.value.saving && vm.editor.state.value.title == "可恢复旧版本" }
            assertEquals("原始版本正文。", repo.find(doc.id)!!.text)
            assertTrue(repo.versions(doc.id).any { it.title == "当前正式版" })
            capture("history-restore-retried")
        } finally { writeSql("DROP TRIGGER IF EXISTS l03_history_failure"); cleanup(doc.id) }
    }

    @Test fun actualExportIoFailureAndMalformedBackupOfferVisibleRecoveryWithoutLosingData() = runBlocking<Unit> {
        ready()
        val doc = repo.create("备份IO保护", "备份失败后仍保留。", DocumentSource.TXT)
        val dir = File(context.cacheDir, "l03-" + UUID.randomUUID()).apply { mkdirs() }
        val target = File(dir, "valid.museumreader")
        try {
            compose.runOnIdle { vm.openSettings(); vm.exportBackup(Uri.fromFile(dir)) }
            compose.waitUntil(8000) { vm.state.value.failure?.operation == UserOperation.BACKUP_EXPORT }
            assertFalse(vm.state.value.isBusy)
            assertFalse(vm.state.value.failure!!.message.contains(dir.path))
            compose.onNodeWithText("重新选择保存位置").assertExists()
            capture("backup-export-io-failed")
            tap("返回")
            compose.runOnIdle { vm.exportBackup(Uri.fromFile(target)) }
            compose.waitUntil(8000) { !vm.state.value.isBusy }
            assertNull(vm.state.value.failure)
            assertTrue(target.length() > 0)
            capture("backup-export-retried")
            val bad = File(dir, "bad.museumreader").apply { writeText("{synthetic broken backup") }
            compose.runOnIdle { vm.importBackup(Uri.fromFile(bad)) }
            compose.waitUntil(8000) { vm.state.value.failure?.operation == UserOperation.BACKUP_READ }
            assertNull(vm.state.value.pendingBackup)
            compose.onNodeWithText("重新选择备份").assertExists()
            assertFalse(vm.state.value.failure!!.message.contains("JSONObject"))
            capture("backup-read-failed")
            tap("返回")
            compose.runOnIdle { vm.importBackup(Uri.fromFile(target)) }
            compose.waitUntil(8000) { vm.state.value.pendingBackup != null }
            assertEquals("备份失败后仍保留。", repo.find(doc.id)!!.text)
            tap("取消")
            compose.waitUntil(6000) { vm.state.value.pendingBackup == null }
            capture("backup-read-retried")
        } finally { target.delete(); File(dir, "bad.museumreader").delete(); dir.delete(); cleanup(doc.id) }
    }

    private fun confirmReplacement() {
        tap(BackupConflictPolicy.REPLACE.label)
        tap("确认以备份内容覆盖冲突文稿")
        tap("确认恢复")
    }

    @Test fun actualRestoreSqlFailureKeepsLocalDataAndReselectThenRetrySucceeds() = runBlocking<Unit> {
        ready()
        val doc = repo.create("备份恢复保护", "备份版本内容。", DocumentSource.TXT)
        val file = File(context.cacheDir, "l03-restore-${UUID.randomUUID()}.museumreader")
        try {
            BackupManager(context, repo).export(Uri.fromFile(file), vm.state.value.settings)
            repo.updateContent(doc.id, doc.title, "更新的本机内容。")
            compose.runOnIdle { vm.openSettings(); vm.importBackup(Uri.fromFile(file)) }
            compose.waitUntil(8000) { vm.state.value.pendingBackup != null }
            writeSql("CREATE TRIGGER l03_restore_failure BEFORE UPDATE ON documents WHEN OLD.id='${doc.id}' BEGIN SELECT RAISE(ABORT, 'private-ui-restore'); END")
            confirmReplacement()
            compose.waitUntil(10000) { vm.state.value.failure?.operation == UserOperation.BACKUP_RESTORE }
            assertFalse(vm.state.value.isBusy)
            assertFalse(vm.state.value.failure!!.message.contains("private-ui-restore"))
            assertEquals("更新的本机内容。", repo.find(doc.id)!!.text)
            capture("backup-restore-failed")
            writeSql("DROP TRIGGER l03_restore_failure")
            tap("返回")
            compose.runOnIdle { vm.importBackup(Uri.fromFile(file)) }
            compose.waitUntil(8000) { vm.state.value.pendingBackup != null }
            confirmReplacement()
            compose.waitUntil(10000) { !vm.state.value.isBusy }
            assertNull(vm.state.value.failure)
            assertEquals("备份版本内容。", repo.find(doc.id)!!.text)
            capture("backup-restore-retried")
            if (vm.state.value.backupRecoveryPoint != null) tap("知道了")
        } finally { writeSql("DROP TRIGGER IF EXISTS l03_restore_failure"); file.delete(); cleanup(doc.id) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutableUi(): MutableStateFlow<AppUiState> = MuseumReaderViewModel::class.java.getDeclaredField("mutableState")
        .apply { isAccessible = true }.get(vm) as MutableStateFlow<AppUiState>

    @Test fun recordingSaveProgressAndSqlFailureProtectTheOldTakeAndRetry() = runBlocking<Unit> {
        ready()
        val doc = repo.create("录音保存保护", "同一句的原录音。", DocumentSource.TXT)
        val sentence = repo.load(doc.id)!!.content.sentences.single()
        val dir = File(context.filesDir, "sentence-audio/l03-${UUID.randomUUID()}")
        val old = File(dir, "old.wav"); val take = File(dir, "take.wav")
        writeTestWav(old); writeTestWav(take)
        val hash = DocumentRepository.textHash(sentence.text)
        val original = SentenceAudio(UUID.randomUUID().toString(), doc.id, sentence.id, AudioKind.RECORDING, "local", "self", old.path, hash, 1000)
        val pending = PendingRecording(UUID.randomUUID().toString(), doc.id, sentence.id, hash, take.path, 1000, true)
        val originalHash = AudioFiles.sha256(old)
        repo.putAudio(original)
        try {
            compose.runOnIdle { vm.openDocument(doc.id) }
            compose.waitUntil(8000) { vm.state.value.currentDocument?.id == doc.id && !vm.state.value.isBusy }
            compose.runOnIdle { vm.openRecording() }
            compose.waitUntil(8000) { vm.state.value.page == AppPage.RECORDING }
            compose.runOnIdle { mutableUi().value = vm.state.value.copy(recording = vm.state.value.recording.copy(pending = pending)) }
            writeSql("CREATE TRIGGER l03_record_failure BEFORE INSERT ON audio_assets WHEN NEW.id='${pending.id}' BEGIN SELECT RAISE(ABORT, 'private-ui-record'); END")
            sql.beginTransaction()
            try {
                tap("确认保存为本人录音")
                compose.waitUntil(6000) { vm.state.value.recording.processing }
                capture("recording-save-progress")
            } finally { sql.endTransaction() }
            compose.waitUntil(8000) { vm.state.value.failure?.operation == UserOperation.RECORD_SAVE }
            assertFalse(vm.state.value.recording.processing)
            assertEquals(pending, vm.state.value.recording.pending)
            assertEquals(original.id, repo.latestAudio(sentence.id, AudioKind.RECORDING)!!.id)
            assertEquals(originalHash, AudioFiles.sha256(old))
            assertTrue(take.isFile)
            capture("recording-save-failed")
            writeSql("DROP TRIGGER l03_record_failure")
            tap("返回"); tap("确认保存为本人录音")
            compose.waitUntil(8000) { vm.state.value.recording.pending == null && !vm.state.value.recording.processing }
            assertNull(vm.state.value.failure)
            assertEquals(pending.id, repo.latestAudio(sentence.id, AudioKind.RECORDING)!!.id)
            assertEquals(originalHash, AudioFiles.sha256(old))
            capture("recording-save-retried")
        } finally {
            writeSql("DROP TRIGGER IF EXISTS l03_record_failure")
            compose.runOnIdle { mutableUi().value = vm.state.value.copy(recording = RecordingUiState()) }
            cleanup(doc.id); old.delete(); take.delete(); dir.delete()
        }
    }

    @Test fun syntheticCloudProgressFailuresAndRetryUseTheRealTaskUiWithoutProviderCalls() = runBlocking<Unit> {
        ready()
        val doc = repo.create("云任务界面保护", "合成测试文字。", DocumentSource.TXT)
        val prefs = CloudSpeechPreferencesRepository(context)
        val before = prefs.load()
        val mode = AtomicInteger(0)
        val gate = AtomicReference(CompletableDeferred<Unit>())
        val entered = AtomicReference(CompletableDeferred<Unit>())
        val wav = File(context.cacheDir, "l03-cloud-${UUID.randomUUID()}.wav")
        writeTestWav(wav)
        val field = MuseumReaderViewModel::class.java.getDeclaredField("cloudSpeechManager").apply { isAccessible = true }
        val previous = field.get(vm)
        val namespace = "l03-synthetic-cloud-${UUID.randomUUID()}-"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(namespace + name, mode)
        }
        val isolatedStore = SecureCredentialStore(isolated)
        isolatedStore.saveBaidu(BaiduCredentials("synthetic-ui-app", "synthetic-ui-key", "synthetic-ui-secret"))
        val credentialField = MuseumReaderViewModel::class.java.getDeclaredField("credentialStore").apply { isAccessible = true }
        val previousStore = credentialField.get(vm)
        try {
            prefs.save(before.copy(enabled = true, dailyCharacterLimit = 200000))
            credentialField.set(vm, isolatedStore)
            val manager = CloudSpeechManager(context, repo, isolatedStore, prefs) { provider, _ ->
                object : CloudSpeechProvider {
                    override val provider = provider
                    override suspend fun synthesize(text: String, voiceId: String): SynthesizedAudio {
                        entered.get().complete(Unit); gate.get().await()
                        when (mode.get()) {
                            0 -> throw CloudServiceException(provider, CloudFailureKind.AUTHENTICATION, 401)
                            1 -> throw SocketTimeoutException("synthetic offline private-host")
                            2 -> throw CloudServiceException(provider, CloudFailureKind.QUOTA, 429)
                        }
                        return SynthesizedAudio(wav.readBytes(), "wav")
                    }
                }
            }
            field.set(vm, manager)
            compose.runOnIdle { vm.openDocument(doc.id) }
            compose.waitUntil(8000) { vm.state.value.currentDocument?.id == doc.id && !vm.state.value.isBusy }
            compose.runOnIdle { vm.openCloudVoice() }
            for ((index, label) in listOf("auth", "network", "quota").withIndex()) {
                mode.set(index); entered.set(CompletableDeferred()); gate.set(CompletableDeferred())
                tap("生成并缓存自然声音")
                withTimeout(8000) { entered.get().await() }
                capture("cloud-$label-progress")
                gate.get().complete(Unit)
                compose.waitUntil(8000) { vm.state.value.failure?.operation == UserOperation.CLOUD && vm.state.value.cloudSpeech.generationTotal == 0 }
                assertFalse(vm.state.value.failure!!.message.contains("private-"))
                assertTrue(vm.state.value.failure!!.message.contains(listOf("鉴权", "超时", "额度")[index]))
                assertEquals("合成测试文字。", repo.find(doc.id)!!.text)
                capture("cloud-$label-failed")
                tap("检查云声音设置")
            }
            mode.set(3); gate.get().complete(Unit)
            tap("生成并缓存自然声音")
            compose.waitUntil(10000) { vm.state.value.cloudSpeech.generationTotal == 0 && runBlocking { repo.audioAssets(doc.id).any { it.kind == AudioKind.CLOUD } } }
            assertNull(vm.state.value.failure)
            assertTrue(repo.audioAssets(doc.id).all { File(it.path).isFile })
            capture("cloud-retried")
        } finally {
            gate.get().complete(Unit)
            compose.runOnIdle { vm.cancelCloudGeneration(); vm.dismissFailure() }
            field.set(vm, previous); credentialField.set(vm, previousStore); prefs.save(before)
            context.deleteSharedPreferences(namespace + "encrypted_cloud_credentials")
            val files = repo.audioAssets(doc.id).map { File(it.path) }
            cleanup(doc.id); files.forEach { it.delete() }; wav.delete()
        }
    }
}

