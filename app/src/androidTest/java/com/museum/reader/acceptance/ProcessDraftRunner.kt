package com.museum.reader.acceptance

import android.app.Activity
import androidx.test.runner.MonitoringInstrumentation
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import com.museum.reader.MainActivity
import com.museum.reader.data.DocumentRepository
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.DocumentSource
import com.museum.reader.ui.AppPage
import com.museum.reader.ui.MuseumReaderViewModel
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Two separate application processes, with adb force-stop between the phases. */
class ProcessDraftRunner : MonitoringInstrumentation() {
    private var phase = ""
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        phase = arguments?.getString("phase").orEmpty()
        start()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Draft process check timed out" }
            Thread.sleep(30)
        }
    }
    override fun onStart() {
        try {
            check(phase == "seed" || phase == "verify")
            val marker = File(targetContext.filesDir, "acceptance-process-draft.json")
            val title = "进程恢复测试草稿标题"
            val text = "正式文稿保持原样。\n\n这是已确认落盘的草稿。"
            val repository = DocumentRepository(targetContext)
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                lateinit var vm: MuseumReaderViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[MuseumReaderViewModel::class.java] }
                await { !vm.state.value.isBusy }
                val id = if (phase == "seed") {
                    check(!marker.exists()) { "A previous draft exercise is preserved; it will not be overwritten" }
                    val doc = runBlocking { repository.create("进程测试正式标题", "正式文稿保持原样。", DocumentSource.TXT) }
                    marker.writeText(JSONObject().put("documentId", doc.id).toString())
                    doc.id
                } else JSONObject(marker.readText()).getString("documentId")
                val formal = runBlocking { repository.find(id) } ?: error("Formal document missing")
                check(formal.title == "进程测试正式标题" && formal.text == "正式文稿保持原样。")
                scenario.onActivity { vm.openEditor(formal) }
                await { vm.state.value.page == AppPage.EDITOR && vm.editor.state.value.document?.id == id }
                if (phase == "seed") {
                    scenario.onActivity { vm.editor.change(title, text); vm.editor.position(3, 9, 0) }
                    await { vm.editor.state.value.let { it.dirty && it.persistedRevision == it.revision } }
                    check(vm.editor.state.value.draftStatus == "草稿已保存，尚未提交正式文稿")
                } else {
                    await { vm.editor.state.value.pendingDraft != null }
                    scenario.onActivity { vm.editor.chooseDraft(true) }
                    val recovered = vm.editor.state.value
                    check(recovered.title == title && recovered.text == text)
                    check(recovered.selectionStart == 3 && recovered.selectionEnd == 9)
                    check(recovered.dirty && recovered.persistedRevision == recovered.revision)
                    scenario.onActivity { vm.editor.discard {} }
                    await { vm.editor.state.value.document == null }
                    runBlocking { ReaderDatabase.get(targetContext).readerDao().purgeDocument(id) }
                    check(marker.delete())
                }
                File(targetContext.getExternalFilesDir(null), "process-draft-$phase.json").writeText(JSONObject()
                    .put("passed", true).put("phase", phase).put("pid", android.os.Process.myPid())
                    .put("titleSha256", DocumentRepository.textHash(title))
                    .put("textSha256", DocumentRepository.textHash(text))
                    .put("formalUnchanged", true).put("timestamp", System.currentTimeMillis()).toString())
            } finally { scenario.close() }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "OK (1 test)\nDraft process $phase verified.\n") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILURES!!!\n" + error.stackTraceToString()) })
        }
    }
}
