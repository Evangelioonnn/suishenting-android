package com.museum.reader.ui

import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.MainActivity
import com.museum.reader.data.*
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A raw damaged recording must stay in the package, with an honest visible export result. */
class BackupExportIntegrityUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun exportingUndecodableAudioKeepsRawBytesAndShowsAnExplicitWarning() = runBlocking<Unit> {
        check(android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
        lateinit var vm: MuseumReaderViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[MuseumReaderViewModel::class.java] }
        compose.waitUntil(8000) { !vm.state.value.isBusy }
        val repo = DocumentRepository(context)
        val dao = ReaderDatabase.get(context).readerDao()
        check(dao.documentSummariesIncludingTrash().isEmpty() && repo.allAudioAssets().isEmpty()) {
            "This exercise only runs on an empty synthetic test application"
        }
        val doc = repo.create("坏音频备份提示", "文稿及原文件都应保留。", DocumentSource.TXT)
        val sentence = repo.load(doc.id)!!.content.sentences.single()
        val directory = File(context.filesDir, "sentence-audio/export-integrity-${UUID.randomUUID()}").apply { mkdirs() }
        val raw = File(directory, "damaged.wav").apply { writeBytes(ByteArray(512) { 1 }) }
        val target = File(context.cacheDir, "export-integrity-${UUID.randomUUID()}.museumreader")
        val before = AudioFiles.sha256(raw)
        var prepared: PreparedBackup? = null
        try {
            assertNull(AudioFiles.inspect(raw))
            repo.putAudio(SentenceAudio(UUID.randomUUID().toString(), doc.id, sentence.id, AudioKind.RECORDING,
                "local", "self", raw.path, DocumentRepository.textHash(sentence.text), 0))
            compose.runOnIdle { vm.openSettings(); vm.exportBackup(Uri.fromFile(target)) }
            compose.waitUntil(10000) { !vm.state.value.isBusy }
            assertNull(vm.state.value.failure); assertTrue(target.length() > 0)
            assertTrue("Successful export must visibly disclose audio that cannot be restored for playback", vm.state.value.backupWarnings.isNotEmpty())
            assertTrue(vm.state.value.backupWarnings.any { it.contains("无法解码") })
            compose.onNodeWithText("备份保存结果").assertExists()
            ZipFile(target).use { zip ->
                val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
                val descriptor = manifest.getJSONArray("audio").getJSONObject(0)
                val bytes = zip.getInputStream(zip.getEntry(descriptor.getString("entry"))).use { it.readBytes() }
                assertArrayEquals(raw.readBytes(), bytes)
            }
            val manager = BackupManager(context, repo)
            prepared = manager.prepare(Uri.fromFile(target))
            assertTrue(prepared!!.preview.warnings.any { it.contains("无法解码") })
            assertEquals(before, AudioFiles.sha256(raw)); assertEquals(doc.text, repo.find(doc.id)!!.text)
        } finally {
            prepared?.let { BackupManager(context, repo).discard(it) }
            compose.runOnIdle { vm.dismissBackupResult(); vm.goHome() }
            dao.purgeDocument(doc.id); raw.delete(); directory.delete(); target.delete()
        }
    }
}
