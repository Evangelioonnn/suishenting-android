package com.museum.reader.acceptance

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.museum.reader.data.AudioFiles
import com.museum.reader.data.DocumentRepository
import com.museum.reader.data.SettingsRepository
import com.museum.reader.data.db.ReaderDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Verifies the actual installed upgrade, separately from setup and ordinary regression discovery. */
class UpgradeVerifyRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            @Suppress("DEPRECATION")
            val code = targetContext.packageManager.getPackageInfo(targetContext.packageName, 0).versionCode
            check(code == com.museum.reader.BuildConfig.VERSION_CODE) { "Expected installed current candidate" }
            val expected = JSONObject(File(targetContext.filesDir, "upgrade-seed.json").readText())
            val docs = expected.getJSONArray("documents")
            val first = docs.getString(0); val second = docs.getString(1)
            val report = runBlocking {
                val repo = DocumentRepository(targetContext)
                val a = repo.load(first) ?: error("Document A missing")
                check(a.document.title == "升级验证文稿A" && a.document.text == "升级保留第一句。升级保留第二句。")
                check(a.document.lastSentence == 1 && a.document.isPinned && a.document.speechRate == 0.8f)
                check(repo.find(second)?.text == "第二站正文。")
                check(a.content.sentences.map { it.id } == listOf(expected.getJSONArray("sentenceIds").getString(0),
                    expected.getJSONArray("sentenceIds").getString(1)))
                check(repo.bookmarks(first).single().sentenceId == a.content.sentences[0].id)
                check(repo.versions(first).single().text == "升级前历史正文。")
                check(repo.playlists().single { it.id == expected.getString("route") }.documentIds == listOf(first, second))
                check(repo.audioAssets(first).size == 2)
                val audio = expected.getJSONArray("audio")
                repeat(audio.length()) { index ->
                    val item = audio.getJSONObject(index); val file = File(item.getString("path"))
                    check(AudioFiles.sha256(file) == item.getString("sha256"))
                    check(AudioFiles.inspect(file) != null)
                }
                val settings = SettingsRepository(targetContext).load()
                check(settings.speechRate == 1.05f && settings.pitch == 1.1f && settings.fontSizeSp == 22f)
                val schema = ReaderDatabase.get(targetContext).openHelper.readableDatabase.version
                check(schema == 2)
                JSONObject().put("passed", true).put("versionCode", code).put("schemaVersion", schema)
                    .put("documents", 2).put("bookmarks", 1).put("versions", 1).put("routes", 1)
                    .put("audioHashesPreserved", true).put("settingsPreserved", true)
                    .put("timestamp", System.currentTimeMillis())
            }
            File(targetContext.getExternalFilesDir(null), "upgrade-verified.json").writeText(report.toString())
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "OK (1 test)\nActual APK upgrade retained documents, audio hashes, bookmarks, routes, history, settings and position.\n") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILURES!!!\n" + error.stackTraceToString()) })
        }
    }
}
