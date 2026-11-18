package com.museum.reader.acceptance

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.museum.reader.data.*
import com.museum.reader.data.db.EditorDraftEntity
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Setup and verify are explicitly invoked on opposite sides of an actual APK cover install. */
class RecordingUpgradeRunner : Instrumentation() {
    private var phase = ""
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        phase = arguments?.getString("phase").orEmpty()
        start()
    }

    override fun onStart() {
        try {
            check(android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
            @Suppress("DEPRECATION")
            val code = targetContext.packageManager.getPackageInfo(targetContext.packageName, 0).versionCode
            val marker = File(targetContext.getExternalFilesDir(null), "recording-upgrade-identity.json")
            val repo = DocumentRepository(targetContext)
            runBlocking {
                when (phase) {
                    "seed" -> {
                        check(code == 21 && !marker.exists())
                        repo.initialize()
                        val dao = ReaderDatabase.get(targetContext).readerDao()
                        check(dao.documentSummariesIncludingTrash().isEmpty() && repo.allAudioAssets().isEmpty() && repo.playlists().isEmpty())
                        val doc = repo.create("覆盖安装", "展览开始。相同的原句。相同的原句。", DocumentSource.TXT)
                        repo.updateContent(doc.id, doc.title, doc.text + "展览结束。")
                        repo.savePosition(doc.id, 2)
                        val sentences = checkNotNull(repo.load(doc.id)).content.sentences
                        val items = JSONArray()
                        for (i in 1..2) {
                            val s = sentences[i]
                            val id = UUID.randomUUID().toString()
                            val file = File(targetContext.filesDir, "sentence-audio/recordings/${doc.id}/$id.wav")
                            writeTestWav(file)
                            java.io.RandomAccessFile(file, "rw").use { it.seek(48); it.writeByte(i * 50) }
                            val hash = DocumentRepository.textHash(s.text)
                            repo.putAudio(SentenceAudio(id, doc.id, s.id, AudioKind.RECORDING, "local", "self", file.path, hash, 1000))
                            items.put(JSONObject().put("id", id).put("ordinal", i).put("sentenceId", s.id)
                                .put("textHash", hash).put("path", file.path).put("sha256", AudioFiles.sha256(file)))
                        }
                        repo.toggleBookmark(doc.id, sentences[2].id)
                        repo.saveDraft(EditorDraftEntity(doc.id, "未提交草稿", "升级以前输入。", 1, 3, 123L))
                        val route = repo.savePlaylist(null, "升级路线", listOf(doc.id))
                        SettingsRepository(targetContext).save(ReaderSettings(fontSizeSp = 22f, speechRate = 1.15f), true)
                        marker.writeText(JSONObject().put("originCode", code).put("documentId", doc.id).put("route", route.id)
                            .put("audio", items).toString())
                    }
                    "verify" -> {
                        check(code == 22 && marker.isFile)
                        val seed = JSONObject(marker.readText())
                        val id = seed.getString("documentId")
                        val doc = checkNotNull(repo.load(id))
                        check(doc.document.text == "展览开始。相同的原句。相同的原句。展览结束。" && doc.document.lastSentence == 2)
                        val hashes = mutableSetOf<String>()
                        val assets = repo.audioAssets(id)
                        check(assets.size == 2)
                        val items = seed.getJSONArray("audio")
                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            val sentence = doc.content.sentences[item.getInt("ordinal")]
                            check(sentence.text == "相同的原句。" && sentence.id == item.getString("sentenceId"))
                            val current = checkNotNull(repo.latestAudio(sentence.id, AudioKind.RECORDING))
                            check(current.documentId == id && current.id == item.getString("id") &&
                                current.textHash == item.getString("textHash") && current.path == item.getString("path"))
                            val file = File(current.path)
                            check(AudioFiles.inspect(file) != null)
                            val hash = AudioFiles.sha256(file)
                            check(hash == item.getString("sha256") && hashes.add(hash))
                        }
                        check(repo.draft(id) == EditorDraftEntity(id, "未提交草稿", "升级以前输入。", 1, 3, 123L))
                        check(repo.versions(id).single().text == "展览开始。相同的原句。相同的原句。")
                        check(repo.bookmarks(id).single().sentenceId == doc.content.sentences[2].id)
                        check(repo.playlists().single { it.id == seed.getString("route") }.documentIds == listOf(id))
                        val settings = SettingsRepository(targetContext).load()
                        check(settings.fontSizeSp == 22f && settings.speechRate == 1.15f)
                        File(marker.parentFile, "recording-upgrade-verified.json").writeText(seed.put("candidateCode", code)
                            .put("distinctAudioHashes", hashes.size).put("passed", true).toString())
                    }
                    else -> error("Expected explicit seed or verify stage")
                }
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "OK (1 test)\n") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILURES!!!\n" + error.stackTraceToString()) })
        }
    }
}
