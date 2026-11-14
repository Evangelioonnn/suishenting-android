package com.museum.reader.model

enum class DocumentSource {
    CLIPBOARD,
    TXT,
    DOCX,
}

data class SentenceSegment(
    val index: Int,
    val paragraphIndex: Int,
    val text: String,
    val id: String = "",
)

data class ParagraphSegment(
    val index: Int,
    val sentenceStart: Int,
    val sentences: List<SentenceSegment>,
) {
    val text: String get() = sentences.joinToString(separator = "") { it.text }
}

data class SegmentedText(
    val normalizedText: String,
    val paragraphs: List<ParagraphSegment>,
) {
    val sentences: List<SentenceSegment> = paragraphs.flatMap { it.sentences }
}

data class ReaderDocument(
    val id: String,
    val title: String,
    val source: DocumentSource,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastSentence: Int = 0,
    val isPinned: Boolean = false,
    val speechRate: Float? = null,
    val sentencePauseMs: Int? = null,
    val paragraphPauseMs: Int? = null,
)

data class ReaderBookmark(
    val id: String,
    val documentId: String,
    val sentenceId: String,
    val sentenceIndex: Int,
    val text: String,
    val createdAt: Long,
)

data class ReaderPlaylist(
    val id: String,
    val title: String,
    val documentIds: List<String>,
    val lastDocumentId: String? = null,
    val needsNameReview: Boolean = false,
)

enum class AudioKind { CLOUD, RECORDING }

data class SentenceAudio(
    val id: String,
    val documentId: String,
    val sentenceId: String,
    val kind: AudioKind,
    val provider: String? = null,
    val voiceId: String? = null,
    val path: String,
    val textHash: String,
    val durationMs: Long = 0,
    val archivedAt: Long? = null,
)

enum class LoopMode {
    NONE,
    ALL,
    RANGE,
}

enum class PlaybackStatus { READY, PLAYING, PAUSED, COMPLETED, ERROR }

data class PlaybackSnapshot(
    val documentId: String? = null,
    val documentTitle: String = "",
    val sentences: List<String> = emptyList(),
    val paragraphIndices: List<Int> = emptyList(),
    val sentenceIds: List<String> = emptyList(),
    val contentVersion: String = "",
    val audioCandidates: Map<String, List<SentenceAudio>> = emptyMap(),
    val speechRate: Float? = null,
    val sentencePauseMs: Int? = null,
    val paragraphPauseMs: Int? = null,
    val currentIndex: Int = 0,
    val status: PlaybackStatus = PlaybackStatus.READY,
    val loopMode: LoopMode = LoopMode.NONE,
    val rangeStart: Int = 0,
    val rangeEnd: Int = 0,
    val error: String? = null,
    val outputSource: String = "待播放",
    val globalSettings: ReaderSettings = ReaderSettings(),
    val cloudEnabled: Boolean = false,
    val preferredCloudProvider: String = "",
    val preferredCloudVoice: String = "",
    val appliedRate: Float? = null,
    val timerDeadlineElapsed: Long? = null,
) {
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING
    val effectiveRate: Float get() = if (isPlaying) appliedRate ?: requestedRate else requestedRate
    val requestedRate: Float get() = speechRate ?: globalSettings.speechRate
}

data class ReaderSettings(
    val speechRate: Float = 0.95f,
    val pitch: Float = 1f,
    val fontSizeSp: Float = 20f,
    val voiceName: String? = null,
    val sentencePauseMs: Int = 300,
    val paragraphPauseMs: Int = 900,
)
