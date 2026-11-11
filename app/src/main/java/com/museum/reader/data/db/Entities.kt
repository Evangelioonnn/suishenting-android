package com.museum.reader.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val source: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastSentence: Int,
    val isPinned: Boolean,
    val speechRate: Float?,
    val sentencePauseMs: Int?,
    val paragraphPauseMs: Int?,
    val trashedAt: Long?,
)

@Entity(
    tableName = "sentences",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["documentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("documentId"), Index(value = ["documentId", "ordinal"])],
)
data class SentenceEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val ordinal: Int,
    val paragraphIndex: Int,
    val text: String,
    val textHash: String,
    val retiredAt: Long? = null,
)

@Entity(
    tableName = "document_versions",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["documentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("documentId")],
)
data class DocumentVersionEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val title: String,
    val text: String,
    val createdAt: Long,
    val sentenceSnapshot: String? = null,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SentenceEntity::class, parentColumns = ["id"], childColumns = ["sentenceId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("documentId"), Index("sentenceId"), Index(value = ["documentId", "sentenceId"], unique = true)],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val sentenceId: String,
    val createdAt: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastDocumentId: String? = null,
)

@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "documentId"],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("playlistId"), Index("documentId")],
)
data class PlaylistItemEntity(
    val playlistId: String,
    val documentId: String,
    val position: Int,
)

@Entity(
    tableName = "audio_assets",
    foreignKeys = [
        ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SentenceEntity::class, parentColumns = ["id"], childColumns = ["sentenceId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("documentId"), Index("sentenceId"), Index(value = ["sentenceId", "kind", "provider", "voiceId"])],
)
data class AudioAssetEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val sentenceId: String,
    val kind: String,
    val provider: String?,
    val voiceId: String?,
    val path: String,
    val textHash: String,
    val durationMs: Long,
    val createdAt: Long,
    val archivedAt: Long? = null,
)

@Entity(
    tableName = "editor_drafts",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["documentId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class EditorDraftEntity(
    @PrimaryKey val documentId: String,
    val title: String,
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val updatedAt: Long,
)

@Entity(tableName = "restore_receipts")
data class RestoreReceiptEntity(
    @PrimaryKey val id: String,
    val committedAt: Long,
)

data class SentenceOwner(val id: String, val documentId: String)
