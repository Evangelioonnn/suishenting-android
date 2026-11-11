package com.museum.reader.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.AudioKind
import com.museum.reader.model.DocumentSource
import com.museum.reader.model.SentenceAudio
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentRepositoryIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun noOpSaveAndRenameKeepBookmarksAudioAndHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val file = File(context.cacheDir, UUID.randomUUID().toString()).apply { writeText("audio fixture") }
        try {
            val repository = DocumentRepository(context, db)
            val document = repository.create("测试", "第一句。第二句。", DocumentSource.TXT)
            val initial = repository.load(document.id)!!
            val sentence = initial.content.sentences.first()
            repository.toggleBookmark(document.id, sentence.id)
            repository.putAudio(SentenceAudio("recording", document.id, sentence.id, AudioKind.RECORDING,
                "local", "self", file.absolutePath, DocumentRepository.textHash(sentence.text)))
            repository.putAudio(SentenceAudio("cloud", document.id, sentence.id, AudioKind.CLOUD,
                "XUNFEI", "xiaoyan", file.absolutePath, DocumentRepository.textHash(sentence.text)))
            repeat(10) { repository.updateContent(document.id, document.title, document.text) }
            repository.updateContent(document.id, "新标题", document.text)
            assertEquals(initial.content.sentences.map { it.id }, repository.load(document.id)!!.content.sentences.map { it.id })
            assertEquals(sentence.id, repository.bookmarks(document.id).single().sentenceId)
            assertEquals("recording", repository.latestAudio(sentence.id, AudioKind.RECORDING)!!.id)
            assertEquals("cloud", repository.latestAudio(sentence.id, AudioKind.CLOUD)!!.id)
            assertTrue(repository.versions(document.id).isEmpty())
            assertEquals("新标题", repository.find(document.id)!!.title)
            assertEquals("audio fixture", file.readText())
        } finally { db.close(); file.delete() }
    }

    @Test fun editingPreservesIdentityAndRetainsRetiredAudioForVersionRestore() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val file = File(context.cacheDir, UUID.randomUUID().toString()).apply { writeText("original audio") }
        try {
            val repository = DocumentRepository(context, db)
            val document = repository.create("重复句测试", "开场。相同句。展厅。相同句。结束。", DocumentSource.TXT)
            val original = repository.load(document.id)!!.content.sentences
            repository.toggleBookmark(document.id, original[1].id)
            repository.putAudio(SentenceAudio("old", document.id, original[2].id, AudioKind.RECORDING,
                "local", "self", file.absolutePath, DocumentRepository.textHash(original[2].text)))
            repository.savePosition(document.id, 3)
            repository.updateContent(document.id, document.title, "新开场。开场。相同句。展厅改。相同句。结束。")
            val edited = repository.load(document.id)!!
            assertEquals(original[1].id, edited.content.sentences[2].id)
            assertEquals(original[3].id, edited.content.sentences[4].id)
            assertNotEquals(original[2].id, edited.content.sentences[3].id)
            assertEquals(4, edited.document.lastSentence)
            assertEquals(1, repository.audioAssets(document.id).size)
            assertTrue(db.readerDao().allSentences(document.id).first { it.id == original[2].id }.retiredAt != null)
            val previous = repository.versions(document.id).first()
            repository.restoreVersion(document.id, previous.id)
            assertEquals(original.map { it.id }, repository.load(document.id)!!.content.sentences.map { it.id })
            assertEquals(original[1].id, repository.bookmarks(document.id).single().sentenceId)
            assertTrue(file.exists())
        } finally { db.close(); file.delete() }
    }

    @Test fun smallEditAvoidsRewritingThousandsOfUnchangedSentencesAndKeepsRelations() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val file = File(context.cacheDir, UUID.randomUUID().toString()).apply { writeText("unchanged audio") }
        try {
            val repo = DocumentRepository(context, db)
            val text = (1..5000).joinToString("\n\n") { "第${it}段保留原句。" }
            val doc = repo.create("小改动的数据库写入", text, DocumentSource.TXT)
            val original = db.readerDao().sentences(doc.id)
            val protected = original[3500]
            repo.toggleBookmark(doc.id, protected.id)
            repo.putAudio(SentenceAudio("small-edit-audio", doc.id, protected.id, AudioKind.RECORDING,
                "local", "self", file.path, protected.textHash))
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TABLE sentence_write_audit (operation TEXT NOT NULL)")
            sql.execSQL("CREATE TRIGGER audit_sentence_insert AFTER INSERT ON sentences BEGIN INSERT INTO sentence_write_audit VALUES ('insert'); END")
            sql.execSQL("CREATE TRIGGER audit_sentence_update AFTER UPDATE ON sentences BEGIN INSERT INTO sentence_write_audit VALUES ('update'); END")
            val changed = text.replace("第2500段保留原句。", "第2500段改后原句。")
            repo.updateContent(doc.id, doc.title, changed)
            val actual = db.readerDao().sentences(doc.id)
            assertEquals(5000, actual.size)
            for (index in original.indices) {
                if (index == 2499) assertNotEquals(original[index].id, actual[index].id)
                else assertEquals(original[index], actual[index])
            }
            val writes = sql.query("SELECT COUNT(*) FROM sentence_write_audit").use {
                assertTrue(it.moveToFirst()); it.getInt(0)
            }
            assertEquals("Only the replaced sentence and its retired predecessor need writes", 2, writes)
            assertEquals(changed, repo.find(doc.id)!!.text)
            assertEquals(text, repo.versions(doc.id).single().text)
            assertEquals(protected.id, repo.bookmarks(doc.id).single().sentenceId)
            assertEquals("small-edit-audio", repo.latestAudio(protected.id, AudioKind.RECORDING)!!.id)
            assertEquals("unchanged audio", file.readText())
        } finally { db.close(); file.delete() }
    }

    @Test fun schemaOneMigrationKeepsDataAndRelationships() = runBlocking {
        val name = "migration-" + UUID.randomUUID() + ".db"
        createVersionOne(name)
        val db = Room.databaseBuilder(context, ReaderDatabase::class.java, name)
            .addMigrations(ReaderDatabase.MIGRATION_1_2).build()
        try {
            assertEquals("原始文稿", db.readerDao().document("document")!!.title)
            assertEquals("sentence", db.readerDao().sentences("document").single().id)
            assertEquals("bookmark", db.readerDao().bookmarks("document").single().id)
            assertEquals("audio", db.readerDao().audioAssets("document").single().id)
            assertEquals("version", db.readerDao().versions("document").single().id)
            assertEquals("playlist", db.readerDao().playlists().single().id)
            assertEquals("document", db.readerDao().playlistItems("playlist").single().documentId)
            assertEquals(2, db.openHelper.writableDatabase.version)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun failedMigrationDoesNotReplaceExistingDatabase() {
        val name = "failed-migration-" + UUID.randomUUID() + ".db"
        createVersionOne(name)
        val db = Room.databaseBuilder(context, ReaderDatabase::class.java, name).addMigrations(
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE sentences ADD COLUMN temporaryMarker INTEGER")
                    error("injected migration failure")
                }
            },
        ).build()
        try {
            assertTrue(runCatching { db.openHelper.writableDatabase }.isFailure)
        } finally { db.close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { old ->
            assertEquals(1, old.version)
            old.rawQuery("SELECT title FROM documents WHERE id='document'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("原始文稿", cursor.getString(0))
            }
        }
        context.deleteDatabase(name)
    }

    private fun createVersionOne(name: String) {
        val payload = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.museum.reader.data.db.ReaderDatabase/1.json").bufferedReader().use { it.readText() }
        val schema = JSONObject(payload).getJSONObject("database")
        val db = context.openOrCreateDatabase(name, 0, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                db.execSQL(entity.getString("createSql").replace("$" + "{TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql")
                        .replace("$" + "{TABLE_NAME}", entity.getString("tableName")))
                }
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES(42,?)", arrayOf(schema.getString("identityHash")))
            db.execSQL("INSERT INTO documents VALUES('document','原始文稿','TXT','第一句。',1,1,0,0,NULL,NULL,NULL,NULL)")
            db.execSQL("INSERT INTO sentences VALUES('sentence','document',0,0,'第一句。','hash')")
            db.execSQL("INSERT INTO bookmarks VALUES('bookmark','document','sentence',1)")
            db.execSQL("INSERT INTO audio_assets VALUES('audio','document','sentence','RECORDING','local','self','/fixture.m4a','hash',1000,1)")
            db.execSQL("INSERT INTO document_versions VALUES('version','document','旧标题','旧内容。',1)")
            db.execSQL("INSERT INTO playlists VALUES('playlist','路线',1,1)")
            db.execSQL("INSERT INTO playlist_items VALUES('playlist','document',0)")
            db.version = 1
        } finally { db.close() }
    }
}
