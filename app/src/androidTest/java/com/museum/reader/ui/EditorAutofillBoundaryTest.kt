package com.museum.reader.ui

import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.MainActivity
import com.museum.reader.data.DocumentRepository
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.DocumentSource
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class EditorAutofillBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun body(view: View): CursorEditText? {
        if (view is CursorEditText) return view
        if (view is ViewGroup) repeat(view.childCount) { body(view.getChildAt(it))?.let { found -> return found } }
        return null
    }

    @Test fun longBodyRejectsAutofillAndReopensAfterSearchWhileTypingAndAccessibilityRemainAvailable() = runBlocking<Unit> {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val repo = DocumentRepository(context)
        val unit = ("自动填充target" + "长".repeat(100)).take(97) + "。\n\n"
        val text = unit.repeat(9999) + unit.take(97) + "长长。"
        assertEquals(1_000_000, text.length)
        val doc = repo.create("长文自动填充保护", text, DocumentSource.TXT)
        lateinit var vm: MuseumReaderViewModel
        val observations = JSONArray()
        val output = File(context.getExternalFilesDir(null), "autofill-boundary-evidence").apply { mkdirs() }
        fun checkpoint(completed: Boolean = false) {
            File(output, "boundary.json").writeText(JSONObject().put("passed", completed).put("api", android.os.Build.VERSION.SDK_INT)
                .put("rounds", observations).put("documentHashUnchanged", completed).put("realFrameworkCalls", true).toString(2))
        }
        try {
            compose.runOnIdle { vm = ViewModelProvider(compose.activity)[MuseumReaderViewModel::class.java] }
            compose.waitUntil(10000) { !vm.state.value.isBusy }
            repeat(3) { round ->
                compose.runOnIdle { vm.openEditor(doc) }
                compose.waitUntil(15000) { vm.editor.state.value.document?.id == doc.id && vm.state.value.page == AppPage.EDITOR }
                compose.runOnIdle {
                    val native = checkNotNull(body(compose.activity.findViewById(android.R.id.content)))
                    assertEquals(View.AUTOFILL_TYPE_NONE, native.autofillType)
                    assertNull("Autofill must never parcel manuscript contents", native.autofillValue)
                    val before = native.text.toString()
                    native.autofill(AutofillValue.forText("unrelated-autofill-value"))
                    assertEquals("An Autofill service must not replace the manuscript", before, native.text.toString())
                    // Exercise real framework entry points including a manual request. This
                    // must remain safe even if another text field already started a session.
                    val autofill = native.context.getSystemService(AutofillManager::class.java)
                    autofill?.requestAutofill(native)
                    autofill?.notifyViewEntered(native)
                    autofill?.notifyValueChanged(native)
                    val accessibility = native.createAccessibilityNodeInfo()
                    assertTrue("The body remains editable through accessibility", accessibility.isEditable)
                    assertTrue(accessibility.isFocusable)
                    native.requestFocus(); native.setSelection(850010, 850011)
                    observations.put(JSONObject().put("round", round).put("autofillEnabled", autofill?.isEnabled == true)
                        .put("autofillType", native.autofillType).put("valueAbsent", native.autofillValue == null)
                        .put("accessibilityEditable", accessibility.isEditable))
                }
                instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_X)
                compose.waitUntil(10000) { vm.editor.state.value.text[850010] == 'x' }
                checkpoint()
                compose.onNodeWithText("更多").performClick()
                compose.onNodeWithText("搜索正文").performClick()
                val query = compose.onNode(hasSetTextAction() and hasText("查找文字", substring = true))
                query.performClick().performTextReplacement("target")
                query.performImeAction()
                compose.waitUntil(10000) {
                    compose.onAllNodesWithText("查找正文").fetchSemanticsNodes().isEmpty() &&
                        compose.onAllNodesWithText("1/10000").fetchSemanticsNodes().isNotEmpty() &&
                        compose.onNodeWithText("1/10000").isDisplayed()
                }
                compose.onNodeWithText("1/10000").assertIsDisplayed()
                compose.onNodeWithContentDescription("关闭搜索").performClick()
                compose.runOnIdle { vm.editor.discard { vm.goHome() } }
                compose.waitUntil(10000) { vm.editor.state.value.document == null }
                compose.waitUntil(10000) { vm.state.value.page == AppPage.HOME }
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("最近文稿"))
                compose.onNodeWithText("最近文稿").assertIsDisplayed()
                assertEquals(text, repo.find(doc.id)!!.text)
            }
            checkpoint(completed = true)
        } finally {
            compose.runOnIdle { vm.editor.discard { vm.goHome() } }
            compose.waitUntil(10000) { vm.editor.state.value.document == null }
            ReaderDatabase.get(context).readerDao().purgeDocument(doc.id)
        }
    }
}
