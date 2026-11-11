package com.museum.reader.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.MainActivity
import com.museum.reader.data.DocumentRepository
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.DocumentSource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EditorSearchImeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun editor(view: View): CursorEditText? {
        if (view is CursorEditText) return view
        if (view is ViewGroup) repeat(view.childCount) { editor(view.getChildAt(it))?.let { found -> return found } }
        return null
    }

    @Test fun searchBrowsingHidesKeyboardAndActualBodyTouchRestoresTyping() = runBlocking<Unit> {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val automation = instrument.uiAutomation
        val flags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val repo = DocumentRepository(context)
        val text = (1..12).joinToString("\n\n") { "第${it}段样本。搜索时保留正文位置。" }
        val doc = repo.create("搜索键盘回归", text, DocumentSource.TXT)
        lateinit var vm: MuseumReaderViewModel
        var vmReady = false
        val observations = JSONArray()
        fun imeVisible() = automation.windows.any {
            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root?.isVisibleToUser == true
        }
        fun waitIme(visible: Boolean) {
            var stableSince = 0L
            compose.waitUntil(8000) {
                val now = SystemClock.elapsedRealtime()
                if (imeVisible() != visible) { stableSince = 0; false }
                else { if (stableSince == 0L) stableSince = now; now - stableSince >= 500 }
            }
        }
        fun record(action: String, expectedStart: Int) {
            waitIme(false)
            compose.runOnIdle {
                val body = editor(compose.activity.findViewById(android.R.id.content))!!
                assertEquals(expectedStart, body.selectionStart)
                assertEquals(expectedStart + 2, body.selectionEnd)
                assertTrue(body.height / context.resources.displayMetrics.density >= 86f)
                observations.put(JSONObject().put("action", action).put("actualImeVisible", false)
                    .put("selectionStart", body.selectionStart).put("selectionEnd", body.selectionEnd))
            }
        }
        try {
            compose.runOnIdle { vm = ViewModelProvider(compose.activity)[MuseumReaderViewModel::class.java]; vmReady = true }
            compose.waitUntil(6000) { !vm.state.value.isBusy }
            compose.runOnIdle { vm.openEditor(doc) }
            compose.waitUntil(6000) { vm.state.value.page == AppPage.EDITOR }
            compose.onNodeWithText("更多").performClick()
            compose.onNodeWithText("搜索正文").performClick()
            val query = compose.onNode(hasSetTextAction() and hasText("查找文字，忽略英文大小写"))
            query.performClick().performTextInput("样本")
            waitIme(true)
            query.performImeAction()
            compose.waitUntil(6000) { compose.onAllNodesWithText("1/12").fetchSemanticsNodes().isNotEmpty() }
            val first = text.indexOf("样本")
            val second = text.indexOf("样本", first + 2)
            record("confirm", first)
            compose.onNodeWithContentDescription("下一处").performClick()
            record("next", second)
            compose.onNodeWithContentDescription("上一处").performClick()
            record("previous", first)
            compose.onNodeWithContentDescription("修改查找文字").performClick()
            val missingQuery = compose.onNode(hasSetTextAction() and hasText("查找文字，忽略英文大小写"))
            missingQuery.performTextReplacement("zz_unmatched")
            missingQuery.performImeAction()
            compose.waitUntil(6000) { compose.onAllNodesWithText("0/0").fetchSemanticsNodes().isNotEmpty() }
            waitIme(false)
            compose.runOnIdle {
                val body = editor(compose.activity.findViewById(android.R.id.content))!!
                assertEquals("No-result search must clear the previous hit", body.selectionStart, body.selectionEnd)
                observations.put(JSONObject().put("action", "zero-result").put("actualImeVisible", false)
                    .put("selectionStart", body.selectionStart).put("selectionEnd", body.selectionEnd))
            }
            automation.takeScreenshot()?.let { image ->
                File(context.getExternalFilesDir(null), "editor-search-zero-result.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            val bounds = Rect()
            compose.runOnIdle {
                val body = editor(compose.activity.findViewById(android.R.id.content))!!
                assertTrue(body.getGlobalVisibleRect(bounds))
            }
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.top + bounds.height() * .25f, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                if (action == MotionEvent.ACTION_DOWN) Thread.sleep(80)
            }
            waitIme(true)
            instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_X)
            compose.waitUntil(6000) { vm.editor.state.value.dirty && vm.editor.state.value.text.contains('x') }
            compose.runOnIdle {
                assertEquals(vm.editor.state.value.text, editor(compose.activity.findViewById(android.R.id.content))!!.text.toString())
            }
            File(context.getExternalFilesDir(null), "editor-search-ime-regression.json").writeText(JSONObject()
                .put("passed", true).put("observations", observations).put("actualBodyTouchRestoredIme", true)
                .put("actualKeyInputPersistedInEditorBuffer", true).put("realDevice", false).toString(2))
        } catch (error: Throwable) {
            automation.takeScreenshot()?.let { image ->
                File(context.getExternalFilesDir(null), "editor-search-ime-failure.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            throw error
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
            if (vmReady) compose.runOnIdle { vm.editor.discard {}; vm.goHome() }
            ReaderDatabase.get(context).readerDao().purgeDocument(doc.id)
        }
    }
}
