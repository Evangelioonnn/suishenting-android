package com.museum.reader.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeEditorLongTextTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun editor(view: View): CursorEditText? {
        if (view is CursorEditText) return view
        if (view is ViewGroup) repeat(view.childCount) { editor(view.getChildAt(it))?.let { result -> return result } }
        return null
    }
    @Test fun millionCharacterEditorKeepsLateSelectionAndScrollThroughSaveAndRecreation() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = DocumentRepository(context)
        lateinit var vm: MuseumReaderViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[MuseumReaderViewModel::class.java] }
        compose.waitUntil(6000) { !vm.state.value.isBusy }
        val text = ("长".repeat(97) + "。\n\n").repeat(9999) + "长".repeat(99) + "。"
        assertEquals(1_000_000, text.length)
        val doc = repo.create("百万字编辑实测", text, DocumentSource.TXT)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            val start = SystemClock.elapsedRealtime()
            compose.runOnIdle { vm.openEditor(doc) }
            compose.waitUntil(15000) { vm.state.value.page == AppPage.EDITOR }
            compose.onNodeWithText("保存").assertIsDisplayed()
            compose.waitForIdle()
            val openMs = SystemClock.elapsedRealtime() - start
            compose.runOnIdle {
                val native = editor(compose.activity.findViewById(android.R.id.content))!!
                native.requestFocus()
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(native, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                native.setSelection(900_000)
                native.bringPointIntoView(900_000)
                native.text.replace(900_000, 900_001, "新")
            }
            compose.waitForIdle()
            var keyboardVisible = false
            val keyboardDeadline = SystemClock.elapsedRealtime() + 8000
            while (!keyboardVisible && SystemClock.elapsedRealtime() < keyboardDeadline) {
                compose.runOnIdle {
                    val native = editor(compose.activity.findViewById(android.R.id.content))!!
                    keyboardVisible = ViewCompat.getRootWindowInsets(native)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                if (!keyboardVisible) Thread.sleep(50)
            }
            assertTrue("Software keyboard must actually be visible", keyboardVisible)
            // Insets alone can stay true briefly during a configuration transition. Check the
            // actual IME window and actual screen coordinates of the persistent toolbar too.
            var toolbarAboveKeyboard = false
            val toolbarDeadline = SystemClock.elapsedRealtime() + 8000
            while (!toolbarAboveKeyboard && SystemClock.elapsedRealtime() < toolbarDeadline) {
                val windows = automation.windows
                val imeTop = windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root?.isVisibleToUser == true }
                    .map { Rect().also(it::getBoundsInScreen) }.filter { !it.isEmpty }.minOfOrNull { it.top }
                val roots = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root }
                // Compose exposes virtual accessibility children but does not implement the
                // provider's text-search shortcut. Traverse those real nodes instead.
                val pending = java.util.ArrayDeque(roots)
                val visibleLabels = mutableSetOf<String>()
                var visited = 0
                while (pending.isNotEmpty() && visited++ < 256 && visibleLabels.size < 2) {
                    val node = pending.removeFirst()
                    if (!node.isEditable && imeTop != null) {
                        val label = node.text?.toString() ?: node.contentDescription?.toString()
                        val bounds = Rect().also(node::getBoundsInScreen)
                        if (label in listOf("保存", "更多") && node.isVisibleToUser && bounds.top >= 0 && bounds.bottom <= imeTop)
                            visibleLabels.add(label!!)
                    }
                    repeat(node.childCount) { node.getChild(it)?.let(pending::addLast) }
                }
                toolbarAboveKeyboard = imeTop != null && visibleLabels.size == 2
                if (!toolbarAboveKeyboard) Thread.sleep(50)
            }
            assertTrue("Keyboard must resize the editor while keeping Save and More on screen", toolbarAboveKeyboard)
            compose.runOnIdle {
                val native = editor(compose.activity.findViewById(android.R.id.content))!!
                native.bringPointIntoView(900_001)
                assertTrue(native.selectionStart >= 900_000)
            }
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(15000) { !vm.editor.state.value.saving && !vm.editor.state.value.dirty }
            compose.runOnIdle {
                val native = editor(compose.activity.findViewById(android.R.id.content))!!
                assertTrue(native.selectionStart >= 900_000)
                assertTrue(native.scrollY > 0)
                assertTrue("Editor body must remain visible with keyboard", native.height >= 48 * context.resources.displayMetrics.density)
            }
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            compose.runOnIdle {
                val native = editor(compose.activity.findViewById(android.R.id.content))!!
                assertTrue(native.selectionStart >= 900_000)
            }
            assertEquals('新', repo.find(doc.id)!!.text[900_000])
            val output = File(context.getExternalFilesDir(null), "long-editor.json")
            output.writeText(JSONObject().put("characters", text.length).put("openMs", openMs)
                .put("sampleSha256", DocumentRepository.textHash(text)).put("deviceApi", android.os.Build.VERSION.SDK_INT)
                .put("scope", "single local smoke; not the required real-phone P95").toString())
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
            ReaderDatabase.get(context).readerDao().purgeDocument(doc.id)
        }
    }
}
