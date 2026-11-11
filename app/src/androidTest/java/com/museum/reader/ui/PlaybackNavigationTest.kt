package com.museum.reader.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.MainActivity
import com.museum.reader.data.*
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.domain.PlaybackCommand
import com.museum.reader.model.*
import com.museum.reader.playback.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaybackNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun editor(view: View): CursorEditText? {
        if (view is CursorEditText) return view
        if (view is ViewGroup) repeat(view.childCount) { editor(view.getChildAt(it))?.let { result -> return result } }
        return null
    }

    @Test fun viewingEditingAndSavingBLeavesAPlayingAndReopeningADoesNotResetLoop() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = DocumentRepository(context)
        val db = ReaderDatabase.get(context)
        lateinit var vm: MuseumReaderViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[MuseumReaderViewModel::class.java] }
        compose.waitUntil(6000) { !vm.state.value.isBusy }
        val a = repo.create("播放会话A", "甲讲解词。乙讲解词。丙讲解词。", DocumentSource.TXT)
        val b = repo.create("查看编辑B", "原来属于B的正文。", DocumentSource.TXT)
        val file = File(context.filesDir, "sentence-audio/navigation-" + UUID.randomUUID() + "/voice.wav")
        try {
            writeTestWav(file)
            for (sentence in repo.load(a.id)!!.content.sentences) {
                repo.putAudio(SentenceAudio(UUID.randomUUID().toString(), a.id, sentence.id, AudioKind.RECORDING,
                    "local", "self", file.path, DocumentRepository.textHash(sentence.text), 1000))
            }
            compose.runOnIdle { vm.openDocument(a.id) }
            compose.waitUntil(6000) { vm.state.value.page == AppPage.READER && !vm.state.value.isBusy }
            compose.runOnIdle { vm.setLoopMode(LoopMode.ALL); vm.playPause() }
            compose.waitUntil(6000) { PlaybackBus.snapshot.value.isPlaying && PlaybackBus.snapshot.value.outputSource == "本人录音" }
            compose.runOnIdle { vm.goHome(); vm.openDocument(b.id) }
            compose.waitUntil(6000) { vm.state.value.currentDocument?.id == b.id && !vm.state.value.isBusy }
            assertEquals(a.id, PlaybackBus.snapshot.value.documentId)
            assertTrue(PlaybackBus.snapshot.value.isPlaying)
            compose.onNodeWithText("播放本文").assertIsDisplayed()
            compose.runOnIdle { vm.openEditor(b) }
            compose.waitUntil(6000) { vm.state.value.page == AppPage.EDITOR }
            compose.onNodeWithText("查看编辑B").performTextReplacement("查看编辑B新标题")
            compose.runOnIdle {
                editor(compose.activity.findViewById(android.R.id.content))!!.setText("已经修改并保存的B正文。")
            }
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(6000) { !vm.editor.state.value.saving && !vm.editor.state.value.dirty }
            assertEquals("查看编辑B新标题", repo.find(b.id)!!.title)
            assertEquals("已经修改并保存的B正文。", repo.find(b.id)!!.text)
            assertEquals(a.id, PlaybackBus.snapshot.value.documentId)
            assertEquals(LoopMode.ALL, PlaybackBus.snapshot.value.loopMode)
            assertTrue(PlaybackBus.snapshot.value.isPlaying)
            compose.runOnIdle { vm.closeEditor(); vm.goHome(); vm.openDocument(a.id) }
            compose.waitUntil(6000) { vm.state.value.currentDocument?.id == a.id && !vm.state.value.isBusy }
            assertTrue(PlaybackBus.snapshot.value.isPlaying)
            assertEquals(LoopMode.ALL, PlaybackBus.snapshot.value.loopMode)
        } finally {
            compose.runOnIdle { PlaybackController.send(context, PlaybackCommand.Clear) }
            db.readerDao().purgeDocument(a.id); db.readerDao().purgeDocument(b.id)
            file.delete(); file.parentFile?.delete()
        }
    }
}
