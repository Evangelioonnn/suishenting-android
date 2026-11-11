package com.museum.reader.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.MainActivity
import com.museum.reader.data.DocumentRepository
import com.museum.reader.data.db.EditorDraftEntity
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.DocumentSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EditorDraftUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun findEditor(view: View): CursorEditText? {
        if (view is CursorEditText) return view
        if (view is ViewGroup) repeat(view.childCount) { findEditor(view.getChildAt(it))?.let { editor -> return editor } }
        return null
    }

    @Test fun choosingDraftInReusedEditorPreservesTitleTextAndNativeSelection() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = DocumentRepository(context)
        val document = repo.create("正式标题", "正式内容保持原样。", DocumentSource.TXT)
        lateinit var vm: MuseumReaderViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[MuseumReaderViewModel::class.java] }
        compose.waitUntil(6000) { !vm.state.value.isBusy }
        try {
            repo.saveDraft(EditorDraftEntity(document.id, "应放弃的草稿", "放弃这一份。", 0, 0, System.currentTimeMillis()))
            compose.runOnIdle { vm.openEditor(document) }
            compose.waitUntil(6000) { vm.editor.state.value.pendingDraft != null }
            compose.onNodeWithText("使用正式版本").performClick()
            compose.waitUntil(6000) { vm.editor.state.value.pendingDraft == null }
            compose.waitUntil(6000) { runBlocking { repo.draft(document.id) == null } }
            compose.onNodeWithContentDescription("返回").performClick()
            repo.saveDraft(EditorDraftEntity(document.id, "恢复后的新标题", "恢复后应完整保留的草稿正文。", 3, 9, System.currentTimeMillis()))
            compose.runOnIdle { vm.openEditor(document) }
            compose.waitUntil(6000) { vm.editor.state.value.pendingDraft != null }
            compose.onNodeWithText("继续草稿").performClick()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1000)
            Thread.sleep(1100) // Let the independent durable-draft write run after native installation.
            compose.runOnIdle {
                val editor = findEditor(compose.activity.findViewById(android.R.id.content))!!
                assertEquals("恢复后的新标题", vm.editor.state.value.title)
                assertEquals("恢复后应完整保留的草稿正文。", editor.text.toString())
                assertEquals(3, editor.selectionStart); assertEquals(9, editor.selectionEnd)
                assertEquals(3, vm.editor.state.value.selectionStart); assertEquals(9, vm.editor.state.value.selectionEnd)
            }
            assertEquals("正式标题", repo.find(document.id)!!.title)
            assertEquals("正式内容保持原样。", repo.find(document.id)!!.text)
            assertEquals("恢复后的新标题", repo.draft(document.id)!!.title)
            assertEquals(3, repo.draft(document.id)!!.selectionStart)
            assertEquals(9, repo.draft(document.id)!!.selectionEnd)
        } finally {
            compose.runOnIdle { vm.editor.discard {}; vm.goHome() }
            compose.waitUntil(6000) { vm.editor.state.value.document == null }
            ReaderDatabase.get(context).readerDao().purgeDocument(document.id)
        }
    }
}
