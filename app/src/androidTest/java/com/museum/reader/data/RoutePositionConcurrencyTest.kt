package com.museum.reader.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.DocumentSource
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RoutePositionConcurrencyTest {
    @Test fun concurrentRouteEditAndContinuePositionCannotLeaveARemovedStationSelected() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repo = DocumentRepository(context, db)
        try {
            val a = repo.create("继续位置甲", "甲。", DocumentSource.TXT)
            val b = repo.create("继续位置乙", "乙。", DocumentSource.TXT)
            val route = repo.savePlaylist(null, "并发路线", listOf(a.id, b.id))
            withTimeout(12000) {
                repeat(40) { iteration ->
                    repo.savePlaylist(route.id, route.title, listOf(a.id, b.id))
                    repo.savePlaylistPosition(route.id, a.id)
                    val start = CompletableDeferred<Unit>()
                    val position = async(Dispatchers.IO) { start.await(); repo.savePlaylistPosition(route.id, b.id) }
                    val edit = async(Dispatchers.IO) { start.await(); repo.savePlaylist(route.id, route.title, listOf(a.id)) }
                    start.complete(Unit); position.await(); edit.await()
                    val result = repo.playlists().single()
                    assertEquals(listOf(a.id), result.documentIds)
                    assertTrue("Continue position references a removed station at iteration $iteration",
                        result.lastDocumentId == null || result.lastDocumentId in result.documentIds)
                }
            }
        } finally { db.close() }
    }
}
