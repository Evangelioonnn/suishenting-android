package com.museum.reader.ui

import android.Manifest
import android.app.UiAutomation
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.MainActivity
import com.museum.reader.data.*
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.*
import com.museum.reader.playback.PlaybackBus
import com.museum.reader.playback.PlaybackController
import com.museum.reader.domain.PlaybackCommand
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Dedicated API33 acceptance: run only on the isolated device with actual TalkBack enabled. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 33, maxSdkVersion = 33)
class ActualTalkBackFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var automation: UiAutomation
    private lateinit var vm: MuseumReaderViewModel
    private val events = JSONArray()
    private var transitions = 0
    private val runName get() = InstrumentationRegistry.getArguments().getString("evidenceName", "actual-talkback")!!
    private val output get() = File(context.getExternalFilesDir(null), runName).apply { mkdirs() }

    private fun waitFor(description: String, timeout: Long = 12000, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= end) error("Timeout: $description")
            SystemClock.sleep(60)
        }
    }
    private fun root() = automation.rootInActiveWindow?.also { it.refresh() }
    private fun labels(node: AccessibilityNodeInfo?) = listOfNotNull(node?.text?.toString(), node?.contentDescription?.toString())
    private fun focusedLabels(node: AccessibilityNodeInfo? = focus()): List<String> =
        labels(node).filter { it.isNotBlank() }.ifEmpty {
            // Compose clickable cards expose the spoken text through their virtual children.
            nodes(node).drop(1).flatMap(::labels).filter { it.isNotBlank() }
        }
    private fun focus() = root()?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.also { it.refresh() }
    private fun activatable(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        repeat(5) { if (current?.isClickable == true || current?.isEditable == true) return true; current = current?.parent }
        return false
    }
    private fun matching(label: String, exact: Boolean, node: AccessibilityNodeInfo?): Boolean = labels(node).any {
        if (exact) it.equals(label, ignoreCase = true) else it.contains(label, ignoreCase = true)
    }
    private fun nodes(node: AccessibilityNodeInfo? = root()): List<AccessibilityNodeInfo> {
        if (node == null || !node.refresh()) return emptyList()
        return listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    }
    private fun shell(command: String): String = automation.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
    private fun hardwareTouch(points: List<Pair<Float, Float>>, contacts: Int = 1) {
        // UiAutomation MotionEvent injection bypasses TalkBack's input filter on this emulator.
        // Send real evdev contacts to the mapped display-0 touch device before that filter.
        val display = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val coordinates = points.joinToString(" ") { (x, y) ->
            "${(x * 32767 / (display.width() - 1)).toInt()} ${(y * 32767 / (display.height() - 1)).toInt()}"
        }
        val commands = "exec 2>&1\nexport CLASSPATH=${instrumentation.context.applicationInfo.sourceDir}\n" +
            "app_process /system/bin com.museum.reader.acceptance.HardwareTouchInjector /dev/input/event2 $contacts $coordinates\n"
        val script = File(context.getExternalFilesDir(null), "talkback-hardware-touch.sh")
        script.writeText(commands)
        // executeShellCommand starts an executable; it does not interpret a multiline script.
        val result = shell("sh ${script.path}")
        events.put(JSONObject().put("inputTiming", result.trim()))
        check(result.trim().startsWith("OK contacts=")) { "Kernel touch input failed: ${result.take(300)}" }
    }
    private fun swipe(next: Boolean) {
        val bounds = Rect().also { checkNotNull(root()).getBoundsInScreen(it) }
        val left = bounds.left + bounds.width() * .25f
        val right = bounds.left + bounds.width() * .75f
        val y = bounds.top + bounds.height() * .45f
        val before = focusedLabels().joinToString("|")
        val start = if (next) left else right; val end = if (next) right else left
        hardwareTouch((0..6).map { index -> Pair(start + (end-start)*index/6, y) })
        SystemClock.sleep(300)
        val after = focusedLabels().joinToString("|")
        if (after.isNotEmpty() && before != after) transitions++
        events.put(JSONObject().put("gesture", if(next) "swipe-right" else "swipe-left")
            .put("before", before.take(500)).put("after", after.take(500)))
    }
    private fun seek(label: String, exact: Boolean = true, clickable: Boolean = true) {
        val target = nodes().firstOrNull { it.isVisibleToUser && matching(label, exact, it) }
        val targetBounds = target?.let { Rect().also(it::getBoundsInScreen) }
        val focusBounds = focus()?.let { Rect().also(it::getBoundsInScreen) }
        var next = targetBounds == null || focusBounds == null || targetBounds.centerY() >= focusBounds.centerY()
        var previous = ""; var repeats = 0
        repeat(120) {
            val focused = focus()
            if (focusedLabels(focused).any { if (exact) it.equals(label, true) else it.contains(label, true) } &&
                (!clickable || activatable(focused))) return
            swipe(next)
            val now = focusedLabels().joinToString("|")
            if (now == previous) repeats++ else repeats = 0
            if (repeats >= 3) { next = !next; repeats = 0 }
            previous = now
        }
        error("TalkBack could not reach: $label")
    }
    private fun activate(label: String, exact: Boolean = true) {
        seek(label, exact)
        val bounds = Rect().also { checkNotNull(focus()).getBoundsInScreen(it) }
        hardwareTouch(listOf(Pair(bounds.exactCenterX(), bounds.exactCenterY())), contacts = 2)
        SystemClock.sleep(450)
        events.put(JSONObject().put("gesture", "double-tap").put("target", label))
    }
    private fun shot(name: String) {
        SystemClock.sleep(300)
        val bitmap = checkNotNull(automation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(output, "events.json").writeText(events.toString(2))
    }
    private fun serviceRunning(): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.isEnabled && manager.isTouchExplorationEnabled &&
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback" }
    }
    private fun longWav(file: File) {
        writeTestWav(file)
        val wav = file.readBytes(); val header = wav.copyOf(44); val sound = wav.copyOfRange(44, wav.size)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 36+sound.size*20).putInt(40, sound.size*20)
        file.outputStream().use { it.write(header); repeat(20) { _ -> it.write(sound) } }
    }

    @Test fun actualTalkBackGesturesCompleteTheMainLocalWorkflow() = runBlocking<Unit> {
        check(android.os.Build.VERSION.SDK_INT == 33) { "Dedicated actual-service API33 scenario" }
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        check(shell("id -u").trim() == "0") { "Dedicated emulator requires adb root for hardware contacts" }
        waitFor("actual TalkBack service and touch exploration") { serviceRunning() }
        automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val repo = DocumentRepository(context)
        val doc = repo.create("无障碍验收文稿", "第一句 target。第二句用于播放控制。", DocumentSource.TXT)
        repo.setPinned(doc.id, true)
        val deleted = repo.create("无障碍恢复样本", "恢复后保持的正文。", DocumentSource.TXT)
        repo.trash(deleted.id)
        val route = repo.savePlaylist(null, "无障碍验收路线", listOf(doc.id))
        val brokenBackup = File(context.cacheDir, "talkback-broken-${UUID.randomUUID()}.museumreader")
        val audioFiles = repo.load(doc.id)!!.content.sentences.map { sentence ->
            val file = File(context.filesDir, "sentence-audio/talkback-${doc.id}/${sentence.id}.wav")
            longWav(file)
            repo.putAudio(SentenceAudio(UUID.randomUUID().toString(), doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", file.path, DocumentRepository.textHash(sentence.text), 20000))
            file
        }
        var scenario: ActivityScenario<MainActivity>? = null
        var passed = false
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { vm = ViewModelProvider(it)[MuseumReaderViewModel::class.java] }
            automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            waitFor("home loaded") { !vm.state.value.isBusy && serviceRunning() }
            activate(doc.title, exact = false)
            waitFor("reader opened through TalkBack") { vm.state.value.page == AppPage.READER && vm.state.value.currentDocument?.id == doc.id }
            shot("01-reader")
            activate("播放本文")
            waitFor("actual playback") { PlaybackBus.snapshot.value.isPlaying }
            activate("暂停")
            waitFor("paused") { !PlaybackBus.snapshot.value.isPlaying }
            shot("02-playback-paused")
            activate("搜索正文")
            activate("查找文字", exact = false)
            instrumentation.sendStringSync("target")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            activate("查看结果")
            waitFor("search result") { nodes().any { labels(it).any { text -> text == "1/1" } } }
            shot("03-search")
            activate("关闭搜索")
            activate("更多"); activate("编辑本文")
            waitFor("editor opened") { vm.state.value.page == AppPage.EDITOR }
            activate("讲解词正文")
            instrumentation.sendStringSync("X")
            waitFor("input reached editor") { vm.editor.state.value.dirty }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            activate("保存")
            waitFor("formal save") { !vm.editor.state.value.saving && !vm.editor.state.value.dirty }
            assertTrue(repo.find(doc.id)!!.text.contains("X"))
            shot("04-editor-saved")
            activate("返回")
            waitFor("reader returned") { vm.state.value.page == AppPage.READER }
            activate("更多"); activate("声音与显示设置")
            waitFor("settings") { vm.state.value.page == AppPage.SETTINGS }
            activate("导出完整备份")
            waitFor("actual document picker") { root()?.packageName?.toString()?.endsWith(".documentsui") == true }
            val name = "museumreader-accessibility-${UUID.randomUUID()}.museumreader"
            val field = checkNotNull(nodes().firstOrNull { it.isEditable })
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
            }))
            val save = if (nodes().any { matching("Save", true, it) }) "Save" else "保存"
            activate(save)
            waitFor("backup returned to app", 20000) { root()?.packageName?.toString() == context.packageName && !vm.state.value.isBusy }
            assertNull(vm.state.value.failure)
            shot("05-backup-exported")
            brokenBackup.writeText("{synthetic malformed backup")
            instrumentation.runOnMainSync { vm.importBackup(android.net.Uri.fromFile(brokenBackup)) }
            waitFor("actual malformed backup error") { vm.state.value.failure?.operation == UserOperation.BACKUP_READ }
            seek("备份未通过校验", clickable = false)
            shot("06-backup-error")
            activate("返回")
            waitFor("error dismissed through TalkBack") { vm.state.value.failure == null }
            activate("存储维护、最近删除与恢复点")
            waitFor("storage") { vm.state.value.page == AppPage.STORAGE && !vm.state.value.isBusy }
            seek(deleted.title, exact = false, clickable = false)
            activate("恢复文稿")
            waitFor("deleted document restored") { runBlocking { repo.summaries().any { it.id == deleted.id } } }
            shot("07-restored")
            activate("返回设置"); activate("返回"); activate("返回")
            waitFor("home") { vm.state.value.page == AppPage.HOME }
            activate("讲解路线")
            waitFor("routes") { vm.state.value.page == AppPage.LIBRARY }
            activate(route.title, exact = false)
            waitFor("route detail") { nodes().any { labels(it).any { text -> text.contains("第 1 站") } } }
            shot("08-route")
            activate("第 1 站", exact = false)
            waitFor("route station opened through TalkBack") { vm.state.value.page == AppPage.READER && vm.state.value.routeContextId == route.id }
            shot("09-route-reader")
            assertTrue("Actual TalkBack gestures must change accessibility focus", transitions >= 5)
            assertTrue(serviceRunning())
            passed = true
            File(output, "summary.json").writeText(JSONObject().put("passed", true).put("actualTalkBackService", true)
                .put("automationDoesNotSuppressServices", true).put("actualGestureFocusTransitions", transitions)
                .put("mainFlow", JSONArray(listOf("home", "reader", "play-pause", "search", "edit-save", "settings", "backup-export", "actual-malformed-backup-error-and-dismiss", "storage-restore", "route")))
                .put("backupFileName", name).put("realDevice", false).put("audibleVerification", false)
                .put("textEntry", "hardware key events; OS save-document filename uses ACTION_SET_TEXT")
                .put("gestureInput", "display-0 evdev touchscreen contacts before the accessibility input filter")
                .put("realCloudCalls", false).toString(2))
        } finally {
            if (!passed) {
                runCatching { shot("failure") }
                File(output, "failure-state.json").writeText(JSONObject().put("passed", false)
                    .put("focus", focusedLabels().joinToString("|")).put("actualGestureFocusTransitions", transitions)
                    .put("serviceRunning", serviceRunning()).toString(2))
            }
            instrumentation.runOnMainSync { PlaybackController.send(context, PlaybackCommand.Stop) }
            scenario?.close()
            repo.deletePlaylist(route.id)
            ReaderDatabase.get(context).readerDao().purgeDocument(doc.id)
            ReaderDatabase.get(context).readerDao().purgeDocument(deleted.id)
            audioFiles.forEach { it.delete() }; audioFiles.firstOrNull()?.parentFile?.delete()
            brokenBackup.delete()
        }
    }
}
