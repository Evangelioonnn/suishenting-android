package com.museum.reader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DocumentEntity::class,
        SentenceEntity::class,
        DocumentVersionEntity::class,
        BookmarkEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        AudioAssetEntity::class,
        EditorDraftEntity::class,
        RestoreReceiptEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun readerDao(): ReaderDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sentences ADD COLUMN retiredAt INTEGER")
                db.execSQL("ALTER TABLE document_versions ADD COLUMN sentenceSnapshot TEXT")
                db.execSQL("ALTER TABLE playlists ADD COLUMN lastDocumentId TEXT")
                db.execSQL("ALTER TABLE audio_assets ADD COLUMN archivedAt INTEGER")
                db.execSQL("DROP INDEX index_audio_assets_sentenceId_kind_provider_voiceId")
                db.execSQL("CREATE INDEX index_audio_assets_sentenceId_kind_provider_voiceId ON audio_assets(sentenceId, kind, provider, voiceId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS editor_drafts (documentId TEXT NOT NULL, title TEXT NOT NULL, text TEXT NOT NULL, selectionStart INTEGER NOT NULL, selectionEnd INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(documentId), FOREIGN KEY(documentId) REFERENCES documents(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS restore_receipts (id TEXT NOT NULL, committedAt INTEGER NOT NULL, PRIMARY KEY(id))")
            }
        }
        @Volatile private var instance: ReaderDatabase? = null

        fun get(context: Context): ReaderDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ReaderDatabase::class.java,
                "museum_reader.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
