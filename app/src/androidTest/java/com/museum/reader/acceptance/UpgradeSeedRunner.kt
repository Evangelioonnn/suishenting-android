package com.museum.reader.acceptance

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import com.museum.reader.data.writeTestWav

/** Setup-only runner, never part of the ordinary regression test discovery. Refuses existing databases. */
class UpgradeSeedRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            @Suppress("DEPRECATION")
            val code = targetContext.packageManager.getPackageInfo(targetContext.packageName, 0).versionCode
            check(code == 8) { "Upgrade fixture requires the installed original v0.8.0 APK" }
            val path = targetContext.getDatabasePath("museum_reader.db")
            check(!path.exists()) { "Existing database will not be overwritten" }
            path.parentFile!!.mkdirs()
            val schema = JSONObject(context.assets.open("com.museum.reader.data.db.ReaderDatabase/1.json")
                .bufferedReader().use { it.readText() }).getJSONObject("database")
            val database = SQLiteDatabase.openOrCreateDatabase(path, null)
            val first = UUID.randomUUID().toString(); val second = UUID.randomUUID().toString()
            val sentenceIds = List(3) { UUID.randomUUID().toString() }
            val texts = listOf("升级保留第一句。", "升级保留第二句。", "第二站正文。")
            val now = System.currentTimeMillis()
            val audio = JSONArray()
            try {
                database.beginTransaction()
                val entities = schema.getJSONArray("entities")
                repeat(entities.length()) { i ->
                    val entity = entities.getJSONObject(i)
                    val name = entity.getString("tableName")
                    database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
                    val indexes = entity.optJSONArray("indices") ?: JSONArray()
                    repeat(indexes.length()) { j ->
                        database.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                repeat(setup.length()) { database.execSQL(setup.getString(it)) }
                fun insert(table: String, values: ContentValues) { check(database.insertOrThrow(table, null, values) > 0) }
                fun document(id: String, title: String, text: String, last: Int) = ContentValues().apply {
                    put("id", id); put("title", title); put("source", "TXT"); put("text", text)
                    put("createdAt", now); put("updatedAt", now); put("lastSentence", last)
                    put("isPinned", if (last == 1) 1 else 0)
                    put("speechRate", 0.8f); put("sentencePauseMs", 300); put("paragraphPauseMs", 700)
                    putNull("trashedAt")
                }
                insert("documents", document(first, "升级验证文稿A", texts[0] + texts[1], 1))
                insert("documents", document(second, "升级验证文稿B", texts[2], 0))
                for (i in texts.indices) {
                    val owner = if (i < 2) first else second
                    insert("sentences", ContentValues().apply {
                        put("id", sentenceIds[i]); put("documentId", owner); put("ordinal", if (i < 2) i else 0)
                        put("paragraphIndex", 0); put("text", texts[i]); put("textHash", hash(texts[i]))
                    })
                }
                insert("bookmarks", ContentValues().apply {
                    put("id", UUID.randomUUID().toString()); put("documentId", first)
                    put("sentenceId", sentenceIds[0]); put("createdAt", now)
                })
                insert("document_versions", ContentValues().apply {
                    put("id", UUID.randomUUID().toString()); put("documentId", first)
                    put("title", "升级前历史标题"); put("text", "升级前历史正文。"); put("createdAt", now - 1000)
                })
                val route = UUID.randomUUID().toString()
                insert("playlists", ContentValues().apply {
                    put("id", route); put("title", "升级验证路线"); put("createdAt", now); put("updatedAt", now)
                })
                listOf(first, second).forEachIndexed { index, id ->
                    insert("playlist_items", ContentValues().apply {
                        put("playlistId", route); put("documentId", id); put("position", index)
                    })
                }
                for (i in 0..1) {
                    val kind = if (i == 0) "RECORDING" else "CLOUD"
                    val file = File(targetContext.filesDir, "sentence-audio/" +
                        (if (i == 0) "recordings" else "cloud") + "/$first/" +
                        sentenceIds[i] + (if (i == 0) "-recording-" else "-xunfei-xiaoyan-") + hash(texts[i]).take(12) + ".wav")
                    writeTestWav(file)
                    insert("audio_assets", ContentValues().apply {
                        put("id", UUID.randomUUID().toString()); put("documentId", first); put("sentenceId", sentenceIds[i])
                        put("kind", kind); put("provider", if (i == 0) "local" else "XUNFEI")
                        put("voiceId", if (i == 0) "self" else "xiaoyan"); put("path", file.path)
                        put("textHash", hash(texts[i])); put("durationMs", 1000); put("createdAt", now)
                    })
                    audio.put(JSONObject().put("path", file.path).put("sha256", bytesHash(file.readBytes())))
                }
                database.version = 1
                database.setTransactionSuccessful()
                database.endTransaction()
                targetContext.getSharedPreferences("reader_settings", 0).edit()
                    .putFloat("speech_rate", 1.05f).putFloat("pitch", 1.1f).putFloat("font_size", 22f)
                    .putInt("sentence_pause_ms", 400).putInt("paragraph_pause_ms", 1000).commit()
                targetContext.getSharedPreferences("storage_migration", 0).edit().putBoolean("room_v1_complete", true).commit()
                val expected = JSONObject().put("documents", JSONArray(listOf(first, second)))
                    .put("sentenceIds", JSONArray(sentenceIds)).put("audio", audio).put("route", route)
                    .put("version", code).put("timestamp", now)
                File(targetContext.filesDir, "upgrade-seed.json").writeText(expected.toString())
                File(targetContext.getExternalFilesDir(null), "upgrade-seed.json").writeText(expected.toString())
            } finally { database.close() }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "OK (1 test)\nOriginal v0.8.0 populated with synthetic v1 data; no existing database overwritten.\n") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILURES!!!\n" + error.stackTraceToString()) })
        }
    }
    private fun hash(text: String) = bytesHash(text.trim().toByteArray())
    private fun bytesHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
