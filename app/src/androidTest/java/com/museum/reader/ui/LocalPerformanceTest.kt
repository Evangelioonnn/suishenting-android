package com.museum.reader.ui

import android.app.ActivityManager
import android.os.Debug
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.MainActivity
import com.museum.reader.data.DocumentRepository
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.DocumentSource
import com.museum.reader.model.ReaderDocument
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/** Dedicated diagnostic, excluded from routine platform discovery. No phone performance claim. */
@OptIn(ExperimentalTestApi::class)
class LocalPerformanceTest {
    private lateinit var compose: AndroidComposeUiTest<MainActivity>
    private val testActivity get() = checkNotNull(compose.activity)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var vm: MuseumReaderViewModel
    private val records = JSONObject()
    private val runName get() = InstrumentationRegistry.getArguments().getString("evidenceName", "local-performance")!!
    private val output get() = File(context.getExternalFilesDir(null), runName).apply { mkdirs() }

    private fun save() { File(output, "measurements.json").writeText(records.toString(2)) }
    private fun editor(view: View): CursorEditText? {
        if (view is CursorEditText) return view
        if (view is ViewGroup) repeat(view.childCount) { editor(view.getChildAt(it))?.let { result -> return result } }
        return null
    }
    private fun frames() {
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val decor = testActivity.window.decorView
            decor.postInvalidateOnAnimation()
            decor.postOnAnimation { decor.postOnAnimation { latch.countDown() } }
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "UI frames stopped" }
    }
    private fun home() {
        compose.runOnIdle { vm.goHome() }
        compose.onNodeWithText("最近文稿").assertIsDisplayed()
        frames()
    }
    private fun open(document: ReaderDocument, firstSentence: String): Long {
        val start = SystemClock.elapsedRealtimeNanos()
        compose.runOnIdle { vm.openDocument(document.id) }
        compose.waitUntil(timeoutMillis = 30000) { !vm.state.value.isBusy && vm.state.value.page == AppPage.READER && vm.state.value.currentDocument?.id == document.id }
        compose.onAllNodesWithText(firstSentence)[0].assertIsDisplayed()
        compose.onNodeWithText("更多").assertIsDisplayed()
        frames()
        return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
    }
    private fun memory(stage: String, round: Int): JSONObject {
        Runtime.getRuntime().gc()
        SystemClock.sleep(150)
        frames()
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val heap = Runtime.getRuntime()
        return JSONObject().put("stage", stage).put("round", round).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("totalPssKiB", info.totalPss).put("dalvikPssKiB", info.dalvikPss).put("nativePssKiB", info.nativePss)
            .put("javaUsedBytesAfterRequestedGc", heap.totalMemory() - heap.freeMemory())
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
    }
    private fun photo(name: String) {
        frames()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun releaseLongTextOpenInputSearchAndRepeatedNavigationMeasurements() = runAndroidComposeUiTest<MainActivity>(testTimeout = 15.minutes) {
        compose = this
        compose.runOnIdle { vm = ViewModelProvider(testActivity)[MuseumReaderViewModel::class.java] }
        compose.waitUntil(timeoutMillis = 12000) { !vm.state.value.isBusy }
        check(!com.museum.reader.BuildConfig.DEBUG) { "Run against the internal signed Release application" }
        val repo = DocumentRepository(context)
        val unit = ("性能target" + "长".repeat(100)).take(97) + "。\n\n"
        assertEquals(100, unit.length)
        val small = unit.repeat(999) + unit.take(97) + "长长。"
        val large = unit.repeat(9999) + unit.take(97) + "长长。"
        assertEquals(100_000, small.length)
        assertEquals(1_000_000, large.length)
        val documents = mutableListOf<ReaderDocument>()
        val opens = JSONObject(); records.put("opensMs", opens)
        val inputs = JSONArray(); records.put("nativeInputToFramesMs", inputs)
        val drawInputs = JSONArray(); records.put("nativeInputDispatchToFirstDrawMs", drawInputs)
        val textInputs = JSONArray(); records.put("nativeInputDispatchToTextChangedMs", textInputs)
        val searches = JSONArray(); records.put("searchChangeConfirmToFramesMs", searches)
        val mem = JSONArray(); records.put("memory", mem)
        val traces = JSONArray(); records.put("navigationRounds", traces)
        val am = context.getSystemService(ActivityManager::class.java)
        records.put("environment", JSONObject().put("api", android.os.Build.VERSION.SDK_INT).put("model", android.os.Build.MODEL)
            .put("densityDpi", context.resources.displayMetrics.densityDpi).put("fontScale", context.resources.configuration.fontScale)
            .put("memoryClassMiB", am.memoryClass).put("availableStorageBytes", context.filesDir.usableSpace)
            .put("buildType", "release").put("realDevice", false).put("phoneAcceptance", false)
            .put("openBoundary", "request dispatch through actual first-sentence and toolbar visibility and two UI frames; home between opens; warm process; parsing and database included")
            .put("inputBoundary", "OS key event dispatch through editor buffer and two frames; selection prepared before timing")
            .put("memoryBoundary", "home with the same 100 manuscripts and retained latest editor; requested GC, 150ms settle, two frames; GC not guaranteed"))
        records.put("samples", JSONArray().put(JSONObject().put("charactersUtf16", small.length).put("sha256", DocumentRepository.textHash(small)))
            .put(JSONObject().put("charactersUtf16", large.length).put("sha256", DocumentRepository.textHash(large))))
        save()
        try {
            documents += repo.create("性能十万字", small, DocumentSource.TXT)
            documents += repo.create("性能百万字", large, DocumentSource.TXT)
            repeat(98) { documents += repo.create("性能列表样本${it.toString().padStart(3, '0')}", "列表测试正文。", DocumentSource.TXT) }
            home()
            compose.waitUntil(timeoutMillis = 10000) { vm.state.value.documents.count { it.id in documents.map { doc -> doc.id } } == 100 }
            assertTrue("Home summaries must not load full long text", vm.state.value.documents.filter { it.id in documents.take(2).map { doc -> doc.id } }.all { it.text.isEmpty() })
            photo("01-hundred-manuscripts")
            for (document in documents.take(2)) {
                val timings = JSONArray(); opens.put(document.text.length.toString(), timings)
                repeat(20) { home(); timings.put(open(document, unit.trim())); save() }
            }
            photo("02-million-reader")
            compose.runOnIdle { vm.openEditor(documents[1]) }
            compose.waitUntil(timeoutMillis = 30000) { vm.state.value.page == AppPage.EDITOR && vm.editor.state.value.document?.id == documents[1].id }
            compose.waitForIdle()
            repeat(20) { round ->
                compose.runOnIdle {
                    val native = checkNotNull(editor(testActivity.findViewById(android.R.id.content)))
                    native.requestFocus(); native.setSelection(850010, 850011); native.bringPointIntoView(850010)
                }
                frames()
                val changed = java.util.concurrent.atomic.AtomicLong(-1)
                val drawn = java.util.concurrent.atomic.AtomicLong(-1)
                val dispatch = java.util.concurrent.atomic.AtomicLong(0)
                val firstDraw = CountDownLatch(1)
                lateinit var body: CursorEditText
                val watcher = object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) {
                        if (dispatch.get() > 0) changed.compareAndSet(-1, (SystemClock.elapsedRealtimeNanos() - dispatch.get()) / 1_000_000)
                    }
                }
                val drawing = android.view.ViewTreeObserver.OnDrawListener {
                    if (changed.get() >= 0 && drawn.compareAndSet(-1, (SystemClock.elapsedRealtimeNanos() - dispatch.get()) / 1_000_000)) firstDraw.countDown()
                }
                compose.runOnIdle {
                    body = checkNotNull(editor(testActivity.findViewById(android.R.id.content)))
                    body.addTextChangedListener(watcher); body.viewTreeObserver.addOnDrawListener(drawing)
                }
                val start = SystemClock.elapsedRealtimeNanos()
                dispatch.set(start)
                val key = if (round % 2 == 0) KeyEvent.KEYCODE_X else KeyEvent.KEYCODE_Y
                instrumentation.sendKeyDownUpSync(key)
                compose.waitForIdle(); frames()
                inputs.put((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000)
                check(firstDraw.await(5, TimeUnit.SECONDS)) { "Input was not drawn" }
                compose.runOnIdle { body.removeTextChangedListener(watcher); body.viewTreeObserver.removeOnDrawListener(drawing) }
                drawInputs.put(drawn.get()); textInputs.put(changed.get())
                assertEquals(if (round % 2 == 0) 'x' else 'y', vm.editor.state.value.text[850010])
                save()
            }
            compose.onNodeWithText("更多").performClick(); compose.onNodeWithText("搜索正文").performClick()
            repeat(20) { round ->
                if (round > 0) compose.onNodeWithContentDescription("修改查找文字").performClick()
                val start = SystemClock.elapsedRealtimeNanos()
                compose.onNode(hasSetTextAction() and hasText("查找文字", substring = true)).performTextReplacement(if (round % 2 == 0) "性能" else "zz_unmatched")
                compose.onNodeWithText("查看结果").performClick()
                val label = if (round % 2 == 0) "1/10000" else "0/0"
                compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(label).assertIsDisplayed(); frames()
                if (round % 2 != 0) compose.runOnIdle {
                    val native = checkNotNull(editor(testActivity.findViewById(android.R.id.content)))
                    assertEquals("Zero-result search must clear the previous hit selection", native.selectionStart, native.selectionEnd)
                }
                searches.put((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000)
                save()
            }
            photo("03-million-search")
            compose.runOnIdle { vm.editor.discard { vm.goHome() } }
            compose.waitUntil(timeoutMillis = 10000) { vm.editor.state.value.document == null }
            home()
            mem.put(memory("before-navigation", 0)); save()
            repeat(40) { index ->
                val document = when (index % 4) { 0 -> documents[1]; 1 -> documents[0]; else -> documents[2 + index] }
                val first = if (index % 4 < 2) unit.trim() else "列表测试正文。"
                val open = open(document, first)
                compose.runOnIdle { vm.openEditor(document) }
                compose.waitUntil(timeoutMillis = 30000) { vm.state.value.page == AppPage.EDITOR && vm.editor.state.value.document?.id == document.id }
                compose.waitForIdle()
                compose.runOnIdle {
                    val native = checkNotNull(editor(testActivity.findViewById(android.R.id.content)))
                    native.requestFocus(); native.setSelection(2, 3)
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_X)
                compose.waitUntil(timeoutMillis = 10000) { vm.editor.state.value.dirty }
                compose.onNodeWithContentDescription("返回").performClick()
                compose.onNodeWithText("放弃修改并退出").performClick()
                compose.waitUntil(timeoutMillis = 10000) { vm.state.value.page != AppPage.EDITOR }
                home()
                traces.put(JSONObject().put("round", index + 1).put("openedCharacters", document.text.length).put("openMs", open).put("editedThenDiscarded", true))
                mem.put(memory("after-navigation", index + 1)); save()
            }
            photo("04-navigation-completed")
            records.put("completed", true).put("rounds", 40).put("anrOrOomObserved", false)
            save()
        } catch (failure: Throwable) {
            records.put("failureType", failure.javaClass.name).put("failureMessage", failure.message)
                .put("failureStack", failure.stackTraceToString())
            save()
            throw failure
        } finally {
            if (!records.optBoolean("completed")) { runCatching { photo("failure") }; records.put("completed", false); save() }
            compose.runOnIdle { vm.dismissFailure(); vm.editor.discard { vm.goHome() } }
            compose.waitUntil(timeoutMillis = 10000) { vm.editor.state.value.document == null }
            for (document in documents) ReaderDatabase.get(context).readerDao().purgeDocument(document.id)
        }
    }
}
