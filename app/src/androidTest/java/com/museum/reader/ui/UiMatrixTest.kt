package com.museum.reader.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.MainActivity
import com.museum.reader.data.*
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.domain.PlaybackCommand
import com.museum.reader.model.*
import com.museum.reader.playback.PlaybackController
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real app UI with synthetic local data. Run at each registered display/font/theme configuration. */
class UiMatrixTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val output get() = File(context.getExternalFilesDir(null),
        InstrumentationRegistry.getArguments().getString("evidenceDir")?.let { "supplemental-evidence-$it" }
            ?: "ui-evidence").apply { mkdirs() }

    private fun editorViewport(): JSONObject {
        var value = JSONObject()
        compose.runOnIdle {
            val body = editor(compose.activity.findViewById(android.R.id.content))!!
            value = JSONObject().put("bodyHeightDp", body.height / context.resources.displayMetrics.density)
                .put("imeInsetsVisible", ViewCompat.getRootWindowInsets(body)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
                .put("selectionStart", body.selectionStart).put("selectionEnd", body.selectionEnd)
                .put("elapsedRealtime", android.os.SystemClock.elapsedRealtime())
        }
        return value
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
        Thread.sleep(800) // Native window transitions/compositor are not covered by Compose idleness.
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        checkNotNull(image) { "Unable to capture UI evidence" }
        File(output, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun editor(view: View): CursorEditText? {
        if (view is CursorEditText) return view
        if (view is ViewGroup) repeat(view.childCount) { editor(view.getChildAt(it))?.let { result -> return result } }
        return null
    }

    private fun confirmSearch() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val flags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            var previousIme: Rect? = null
            var stableSince = 0L
            var imeBounds: Rect? = null
            compose.waitUntil(5000) {
                val window = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                val bounds = window?.let { Rect().also(it::getBoundsInScreen) }
                val now = SystemClock.elapsedRealtime()
                if (bounds == null || bounds.isEmpty) { previousIme = null; stableSince = now; false }
                else if (bounds == previousIme && now - stableSince >= 500) { imeBounds = bounds; true }
                else { if (bounds != previousIme) { previousIme = Rect(bounds); stableSince = now }; false }
            }
            compose.onNodeWithText("查看结果").performScrollTo()
            compose.waitForIdle()
            var previousButton: Rect? = null
            var buttonSince = 0L
            var target: Rect? = null
            compose.waitUntil(5000) {
                var candidate: Rect? = null
                for (window in automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .sortedByDescending { if (it.isFocused) 2 else if (it.isActive) 1 else 0 }) {
                    val root = window.root ?: continue
                    val pending = java.util.ArrayDeque(listOf(root))
                    var visited = 0
                    while (pending.isNotEmpty() && visited++ < 512) {
                        val node = pending.removeFirst()
                        if (!node.refresh()) continue
                        val label = node.text?.toString() ?: node.contentDescription?.toString()
                        if (node.isVisibleToUser && label == "查看结果") {
                            val bounds = Rect().also(node::getBoundsInScreen)
                            if (!bounds.isEmpty) candidate = bounds
                        }
                        repeat(node.childCount) { node.getChild(it)?.let(pending::addLast) }
                    }
                    if (candidate != null) break
                }
                val now = SystemClock.elapsedRealtime()
                if (candidate != null && candidate == previousButton && now - buttonSince >= 500) {
                    target = candidate; true
                } else {
                    if (candidate != previousButton) { previousButton = candidate?.let(::Rect); buttonSince = now }
                    false
                }
            }
            val bounds = checkNotNull(target)
            assertTrue("Search confirmation must be above the actual keyboard", bounds.bottom <= imeBounds!!.top)
            val path = File(output, "search-confirm-actions.json")
            val actions = if (path.exists()) JSONArray(path.readText()) else JSONArray()
            actions.put(JSONObject().put("actualImeBounds", imeBounds!!.toShortString())
                .put("actualButtonBounds", bounds.toShortString()).put("actualOsTouch", true))
            path.writeText(actions.toString(2))
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                if (action == MotionEvent.ACTION_DOWN) Thread.sleep(100)
            }
            compose.waitUntil(5000) {
                compose.onAllNodes(hasSetTextAction() and hasText("查找文字，忽略英文大小写"))
                    .fetchSemanticsNodes().isEmpty()
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
        }
    }

    @Test fun mainScreensKeepCriticalActionsReachable() = runBlocking<Unit> {
        val initialConfig = context.resources.configuration
        File(output, "configuration.json").writeText(JSONObject()
            .put("widthDp", initialConfig.screenWidthDp).put("heightDp", initialConfig.screenHeightDp)
            .put("fontScale", initialConfig.fontScale).put("uiMode", initialConfig.uiMode)
            .put("timestamp", System.currentTimeMillis()).toString())
        lateinit var vm: MuseumReaderViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[MuseumReaderViewModel::class.java] }
        compose.waitUntil(6000) { !vm.state.value.isBusy }
        val repo = DocumentRepository(context)
        val db = ReaderDatabase.get(context)
        val ids = ArrayList<String>()
        val dir = File(context.filesDir, "sentence-audio/ui-" + UUID.randomUUID()).apply { mkdirs() }
        var routeId: String? = null
        try {
            val doc = repo.create("界面验收：展厅开场与长标题文稿",
                (1..12).joinToString("\n\n") { "第 $it 段。这里是用于界面核对的样本文字。可以搜索、标记重点，也可以逐句录音。" },
                DocumentSource.TXT).also { ids += it.id }
            val second = repo.create("下一站：器物与工艺", "另一站的样本。", DocumentSource.TXT).also { ids += it.id }
            val removed = repo.create("恢复区中的样本文稿", "可恢复资料。", DocumentSource.TXT).also { ids += it.id }
            repo.trash(removed.id)
            repo.setPinned(doc.id, true)
            val loaded = repo.load(doc.id)!!
            val sentence = loaded.content.sentences[1]
            repo.toggleBookmark(doc.id, sentence.id)
            val file = File(dir, "current.wav"); val old = File(dir, "old.wav")
            writeTestWav(file); writeTestWav(old)
            repo.putAudio(SentenceAudio(UUID.randomUUID().toString(), doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", file.path, DocumentRepository.textHash(sentence.text), 1000))
            repo.putAudio(SentenceAudio(UUID.randomUUID().toString(), doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", old.path, DocumentRepository.textHash(sentence.text), 1000, System.currentTimeMillis()))
            routeId = repo.savePlaylist(null, "样本参观路线", listOf(doc.id, second.id, removed.id)).id
            repo.savePlaylistPosition(routeId, second.id)
            compose.runOnIdle {
                PlaybackController.send(context, PlaybackCommand.Load(PlaybackSnapshot(documentId = doc.id,
                    documentTitle = doc.title, sentences = loaded.content.sentences.map { it.text },
                    sentenceIds = loaded.content.sentences.map { it.id }, currentIndex = 1,
                    contentVersion = loaded.contentVersion, loopMode = LoopMode.RANGE, rangeStart = 1, rangeEnd = 5)))
                vm.goHome()
            }
            compose.waitUntil(6000) { vm.state.value.documents.any { it.id == doc.id } }
            capture("01-home")
            compose.onNodeWithContentDescription("设置").assertIsDisplayed()
            compose.runOnIdle { vm.openDocument(doc.id) }
            compose.waitUntil(6000) { vm.state.value.page == AppPage.READER && !vm.state.value.isBusy }
            val readerViewport = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot.height /
                context.resources.displayMetrics.density
            assertTrue("Reader viewport too small: $readerViewport dp", readerViewport >= 86f)
            capture("02-reader")
            compose.onNodeWithContentDescription("搜索正文").performClick()
            compose.onNode(hasSetTextAction() and hasText("查找文字，忽略英文大小写")).performTextInput("样本")
            confirmSearch()
            compose.waitUntil(5000) { compose.onAllNodesWithText("1/12").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("下一处").performClick()
            compose.onNodeWithContentDescription("从命中句朗读").assertIsDisplayed().assertIsEnabled()
            val searchViewport = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot.height /
                context.resources.displayMetrics.density
            assertTrue("Search viewport too small: $searchViewport dp", searchViewport >= 86f)
            compose.onNodeWithText("2/12").assertExists()
            capture("03-search")
            compose.onNodeWithContentDescription("关闭搜索").performClick()
            compose.runOnIdle { vm.setLoopMode(LoopMode.RANGE) }
            compose.onNodeWithText("请选择起点").assertIsDisplayed()
            capture("04-range")
            compose.runOnIdle { vm.cancelRangeSelection(); vm.openSettings() }
            capture("05-settings")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("试听当前声音"))
            capture("06-settings-voice")
            compose.runOnIdle { vm.openCloudVoice() }
            capture("07-cloud")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("百度智能云密钥"))
            capture("08-cloud-credentials")
            compose.runOnIdle { vm.closeCloudVoice(); vm.closeSettings(); vm.openEditor(doc) }
            compose.waitUntil(6000) { vm.state.value.page == AppPage.EDITOR }
            compose.onNodeWithText("保存").assertIsDisplayed()
            compose.runOnIdle {
                val body = editor(compose.activity.findViewById(android.R.id.content))!!
                val heightDp = body.height / context.resources.displayMetrics.density
                assertTrue("Editor viewport too small: $heightDp dp", heightDp >= 86f)
            }
            capture("09-editor")
            compose.onNodeWithText("更多").performClick()
            compose.onNodeWithText("搜索正文").performClick()
            compose.onNode(hasSetTextAction() and hasText("查找文字，忽略英文大小写")).performTextInput("样本")
            confirmSearch()
            compose.waitUntil(5000) { compose.onAllNodesWithText("1/12").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("下一处").performClick()
            compose.onNodeWithText("2/12").assertIsDisplayed()
            // Dialog dismissal and the native keyboard animation are outside Compose idleness.
            // Keep the original 86dp requirement and verify the requested hidden-keyboard state.
            File(output, "editor-search-viewport-initial.json").writeText(editorViewport().toString(2))
            compose.waitUntil(6000) {
                val observation = editorViewport()
                observation.getDouble("bodyHeightDp") >= 86.0 && !observation.getBoolean("imeInsetsVisible")
            }
            File(output, "editor-search-viewport-stable.json").writeText(editorViewport().toString(2))
            compose.runOnIdle {
                val body = editor(compose.activity.findViewById(android.R.id.content))!!
                val heightDp = body.height / context.resources.displayMetrics.density
                assertTrue("Editor search viewport too small: $heightDp dp", heightDp >= 86f)
            }
            capture("09b-editor-search")
            compose.onNodeWithContentDescription("关闭搜索").performClick()
            compose.runOnIdle { vm.closeEditor(); vm.openLibrary() }
            compose.waitUntil(6000) { vm.state.value.page == AppPage.LIBRARY && vm.state.value.playlists.any { it.id == routeId } }
            compose.runOnIdle { vm.selectRoute(routeId) }
            compose.onNodeWithText("编辑").assertIsDisplayed()
            capture("10-route")
            compose.onNodeWithText("编辑").performClick()
            capture("11-route-edit")
            compose.onNodeWithText("取消").performScrollTo().performClick()
            compose.runOnIdle { vm.openDocument(doc.id) }
            compose.waitUntil(6000) { vm.state.value.page == AppPage.READER && !vm.state.value.isBusy }
            compose.runOnIdle { vm.openRecording() }
            compose.waitUntil(6000) { vm.state.value.page == AppPage.RECORDING }
            capture("12-recording")
            compose.runOnIdle { vm.closeRecording(); vm.openStorage() }
            compose.waitUntil(6000) { vm.state.value.page == AppPage.STORAGE && !vm.state.value.isBusy }
            capture("13-storage")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("最近删除"))
            capture("14-trash")
            val config = context.resources.configuration
            File(output, "configuration.json").writeText(JSONObject()
                .put("widthDp", config.screenWidthDp).put("heightDp", config.screenHeightDp)
                .put("fontScale", config.fontScale).put("uiMode", config.uiMode)
                .put("timestamp", System.currentTimeMillis()).toString())
        } catch (failure: Throwable) {
            runCatching { File(output, "failure-state.json").writeText(editorViewport().put("error", failure.toString()).toString(2)) }
            runCatching { capture("failure") }
            throw failure
        } finally {
            compose.runOnIdle { PlaybackController.send(context, PlaybackCommand.Clear) }
            routeId?.let { repo.deletePlaylist(it) }
            ids.forEach { db.readerDao().purgeDocument(it) }
            dir.listFiles().orEmpty().forEach { it.delete() }; dir.delete()
        }
    }
}
