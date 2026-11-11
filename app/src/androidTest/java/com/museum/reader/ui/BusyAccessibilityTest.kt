package com.museum.reader.ui

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.MainActivity
import com.museum.reader.data.db.ReaderDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class BusyAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun labels(node: AccessibilityNodeInfo?): List<String> {
        if (node == null || !node.refresh()) return emptyList()
        val own = if (node.isVisibleToUser) listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) else emptyList()
        return own + (0 until node.childCount).flatMap { labels(node.getChild(it)) }
    }
    @Test fun actualBusyWindowHidesBackgroundActionsFromAccessibilityUntilFinished() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var vm: MuseumReaderViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[MuseumReaderViewModel::class.java] }
        compose.waitUntil(10000) { !vm.state.value.isBusy }
        compose.runOnIdle { vm.openSettings() }
        compose.waitForIdle()
        compose.waitUntil(8000) { labels(instrumentation.uiAutomation.rootInActiveWindow).any { it.contains("导出完整备份") } }
        val before = labels(instrumentation.uiAutomation.rootInActiveWindow)
        assertTrue(before.any { it.contains("导出完整备份") })
        val sql = ReaderDatabase.get(context).openHelper.writableDatabase
        val syntheticText = "忙碌操作无障碍样本${java.util.UUID.randomUUID()}。"
        sql.beginTransaction()
        try {
            compose.runOnIdle { vm.createFromClipboard(syntheticText) }
            compose.waitUntil(8000) { vm.state.value.isBusy }
            compose.waitForIdle(); SystemClock.sleep(500)
            val busy = labels(instrumentation.uiAutomation.rootInActiveWindow)
            val name = InstrumentationRegistry.getArguments().getString("evidenceName", "busy-accessibility")!!
            val output = File(context.getExternalFilesDir(null), name).apply { mkdirs() }
            File(output, "busy-window.json").writeText(JSONObject().put("beforeVisibleLabels", JSONArray(before))
                .put("busyVisibleLabels", JSONArray(busy)).put("busy", vm.state.value.isBusy)
                .put("actualSqlGate", true).put("actualOsAccessibilityTree", true).toString(2))
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(output, "busy-window.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            assertTrue(busy.any { it.contains("正在分句并保存文稿") })
            assertFalse("Screen reader must not activate export behind the busy layer", busy.any { it.contains("导出完整备份") })
            assertFalse("Screen reader must not activate settings behind the busy layer", busy.any { it.contains("声音与显示") || it.contains("恢复全局默认") })
        } finally {
            sql.endTransaction()
            compose.waitUntil(12000) { !vm.state.value.isBusy }
            val created = vm.state.value.currentDocument?.takeIf { it.text == syntheticText }?.id
            compose.runOnIdle { vm.goHome() }
            if (created != null) kotlinx.coroutines.runBlocking { ReaderDatabase.get(context).readerDao().purgeDocument(created) }
        }
    }
}
