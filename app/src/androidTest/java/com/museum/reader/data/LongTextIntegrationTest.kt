package com.museum.reader.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.data.db.EditorDraftEntity
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.DocumentSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LongTextIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun millionChineseCharactersSurviveBodySentenceDraftAndVersionRoundTrip() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        val text = "长".repeat(999_999) + "。"
        try {
            val doc = repo.create("百万字数据库边界", text, DocumentSource.TXT)
            val loaded = repo.load(doc.id)!!
            assertTrue("body round trip", text == loaded.document.text)
            assertTrue("sentence round trip", text == loaded.content.sentences.single().text)
            repo.saveDraft(EditorDraftEntity(doc.id, "草稿", text, 900_000, 900_001, 1))
            assertTrue("draft round trip", text == repo.draft(doc.id)!!.text)
            repo.updateContent(doc.id, doc.title, text.dropLast(2) + "新。")
            assertTrue("history round trip", text == repo.versions(doc.id).single().text)
            repo.restoreVersion(doc.id, repo.versions(doc.id).single().id)
            assertTrue("restored history", text == repo.find(doc.id)!!.text)
        } finally { db.close() }
    }
}
