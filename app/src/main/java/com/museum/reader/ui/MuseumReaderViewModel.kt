package com.museum.reader.ui

import android.app.Application
import android.net.Uri
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.museum.reader.data.DocumentImporter
import com.museum.reader.data.BackupManager
import com.museum.reader.data.BackupConflictPolicy
import com.museum.reader.data.PreparedBackup
import com.museum.reader.data.DocumentRepository
import com.museum.reader.data.DocumentVersion
import com.museum.reader.data.ImportedDocument
import com.museum.reader.data.LoadedDocument
import com.museum.reader.data.SettingsRepository
import com.museum.reader.data.StorageMaintenance
import com.museum.reader.data.StorageOverview
import com.museum.reader.data.StorageAction
import com.museum.reader.cloud.BaiduCredentials
import com.museum.reader.cloud.CloudProvider
import com.museum.reader.cloud.CloudSpeechManager
import com.museum.reader.cloud.CloudSpeechPreferences
import com.museum.reader.cloud.CloudSpeechPreferencesRepository
import com.museum.reader.cloud.CloudSpeechUiState
import com.museum.reader.cloud.SecureCredentialStore
import com.museum.reader.cloud.XunfeiCredentials
import com.museum.reader.domain.PlaybackRules
import com.museum.reader.domain.TextSegmenter
import com.museum.reader.model.AudioKind
import com.museum.reader.model.DocumentSource
import com.museum.reader.model.LoopMode
import com.museum.reader.model.ReaderBookmark
import com.museum.reader.model.ReaderDocument
import com.museum.reader.model.ReaderPlaylist
import com.museum.reader.model.ReaderSettings
import com.museum.reader.model.SegmentedText
import com.museum.reader.model.SentenceAudio
import com.museum.reader.playback.PlaybackController
import com.museum.reader.domain.PlaybackCommand
import com.museum.reader.domain.SleepDeadline
import com.museum.reader.model.PlaybackSnapshot
import com.museum.reader.model.PlaybackStatus
import com.museum.reader.playback.PlaybackBus
import com.museum.reader.playback.TtsPlaybackService
import com.museum.reader.recording.SentenceRecordingManager
import com.museum.reader.recording.PendingRecording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppPage { HOME, READER, SETTINGS, EDITOR, LIBRARY, CLOUD_VOICE, RECORDING, STORAGE }
enum class RangeSelectionStage { START, END }
enum class TtsStatus { LOADING, READY, MISSING, ERROR }

data class VoiceChoice(val name: String, val label: String)

data class RecordingUiState(
    val currentIndex: Int = 0,
    val isRecording: Boolean = false,
    val amplitude: Float = 0f,
    val pending: PendingRecording? = null,
    val processing: Boolean = false,
    val exitConfirm: Boolean = false,
    val permissionDenied: Boolean = false,
    val recordedSentenceIds: Set<String> = emptySet(),
    val recordingBytes: Long = 0,
    val cloudBytes: Long = 0,
)

data class AppUiState(
    val page: AppPage = AppPage.HOME,
    val documents: List<ReaderDocument> = emptyList(),
    val currentDocument: ReaderDocument? = null,
    val content: SegmentedText = SegmentedText("", emptyList()),
    val readerLayout: com.museum.reader.domain.ReaderLayout? = null,
    val settings: ReaderSettings = ReaderSettings(),
    val loopMode: LoopMode = LoopMode.NONE,
    val rangeStart: Int = 0,
    val rangeEnd: Int = 0,
    val rangeSelectionStage: RangeSelectionStage? = null,
    val voiceChoices: List<VoiceChoice> = emptyList(),
    val ttsStatus: TtsStatus = TtsStatus.LOADING,
    val recentlyDeleted: ReaderDocument? = null,
    val bookmarks: List<ReaderBookmark> = emptyList(),
    val playlists: List<ReaderPlaylist> = emptyList(),
    val routeContextId: String? = null,
    val versions: List<DocumentVersion> = emptyList(),
    val pendingImport: ImportedDocument? = null,
    val pendingBackup: PreparedBackup? = null,
    val backupWarnings: List<String> = emptyList(),
    val backupRecoveryPoint: String? = null,
    val timerEndAt: Long? = null,
    val viewedIndex: Int = 0,
    val cloudSpeech: CloudSpeechUiState = CloudSpeechUiState(),
    val recording: RecordingUiState = RecordingUiState(),
    val storage: StorageOverview = StorageOverview(),
    val pendingRecordings: List<PendingRecording> = emptyList(),
    val isBusy: Boolean = true,
    val busyLabel: String = "正在整理文稿…",
    val message: String? = null,
    val failure: OperationFailure? = null,
)

class MuseumReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DocumentRepository(application)
    private val settingsRepository = SettingsRepository(application)
    private val backupManager = BackupManager(application, repository)
    private val cloudPreferences = CloudSpeechPreferencesRepository(application)
    private val credentialStore = SecureCredentialStore(application)
    private val cloudSpeechManager = CloudSpeechManager(application, repository, credentialStore, cloudPreferences)
    private val storageMaintenance = StorageMaintenance(application, repository)
    private val recordingManager = SentenceRecordingManager(application, repository)
    private val mutableState = MutableStateFlow(AppUiState(settings = settingsRepository.load()))
    val state = mutableState.asStateFlow()
    private val operationErrors = kotlinx.coroutines.CoroutineExceptionHandler { _, error -> showFailure(UserOperation.GENERAL, error) }

    val editor = EditorController(repository, viewModelScope) { loaded ->
        val assets = withContext(Dispatchers.IO) { repository.audioAssets(loaded.document.id) }
        PlaybackController.send(getApplication(), PlaybackCommand.Refresh(queueFor(loaded, assets)))
        if (mutableState.value.currentDocument?.id == loaded.document.id) {
            currentAudio = assets
            viewedVersion = loaded.contentVersion
            val bookmarks = withContext(Dispatchers.IO) { repository.bookmarks(loaded.document.id) }
            mutableState.value = mutableState.value.copy(currentDocument = loaded.document,
                content = loaded.content, readerLayout = loaded.display, bookmarks = bookmarks)
        }
        refreshDocuments()
    }

    private var pendingAudioExport: String? = null
    private var settingsReturnPage = AppPage.HOME
    private var editorReturnPage = AppPage.HOME
    private var voiceLoader: TextToSpeech? = null
    private var voicePreview: TextToSpeech? = null
    private var loadGeneration = 0L
    private var rangeBefore: Triple<LoopMode, Int, Int>? = null
    private var viewedVersion = ""
    private var cloudGenerationJob: Job? = null
    private var amplitudeJob: Job? = null
    private var previewPlayer: MediaPlayer? = null
    private var previewJob: Job? = null
    private var previewGeneration = 0L
    private var cloudGeneration = 0L
    private var currentAudio: List<SentenceAudio> = emptyList()

    init {
        recordingManager.onInterrupted = { stopRecording(save = true) }
        refreshCloudState()
        viewModelScope.launch(operationErrors) {
            runOperationCatching {
                withContext(Dispatchers.IO) {
                    repository.initialize()
                    val recovery = backupManager.recoverInterruptedRestores() + storageMaintenance.recover()
                    Triple(repository.summaries(), repository.playlists(), recovery)
                }
            }.onSuccess { (documents, playlists, recovery) ->
                PlaybackController.restore(getApplication()) { id ->
                    withContext(Dispatchers.IO) {
                        repository.load(id)?.let { queueFor(it, repository.audioAssets(id)) }
                    }
                }
                mutableState.value = mutableState.value.copy(documents = documents, playlists = playlists, isBusy = false,
                    message = recovery.takeIf { it.isNotEmpty() }?.joinToString("；"))
            }.onFailure { error ->
                showFailure(UserOperation.OPEN, error)
            }
        }
        viewModelScope.launch(operationErrors) {
            PlaybackBus.snapshot.collect { if (it.isPlaying) stopPreviews() }
        }
    }

    fun createFromClipboard(text: String) {
        if (text.isBlank()) return showMessage("剪贴板里没有文字")
        createDocument(defaultClipboardTitle(), text, DocumentSource.CLIPBOARD)
    }

    fun importFile(uri: Uri) {
        mutableState.value = mutableState.value.copy(isBusy = true, busyLabel = "正在读取并解析文件…")
        viewModelScope.launch(operationErrors) {
            runOperationCatching {
                withContext(Dispatchers.IO) { DocumentImporter.import(getApplication<Application>().contentResolver, uri) }
            }.onSuccess { imported ->
                mutableState.value = mutableState.value.copy(isBusy = false, pendingImport = imported)
            }.onFailure { error ->
                showFailure(UserOperation.IMPORT, error)
            }
        }
    }

    fun confirmImport(title: String, text: String) {
        val imported = mutableState.value.pendingImport ?: return
        mutableState.value = mutableState.value.copy(pendingImport = null)
        createDocument(title, text, imported.source)
    }

    fun cancelImport() {
        mutableState.value = mutableState.value.copy(pendingImport = null)
    }

    fun openDocument(id: String) {
        mutableState.value = mutableState.value.copy(routeContextId = null)
        loadDocument(id)
    }

    private fun loadDocument(id: String) {
        stopPreviews()
        val request = ++loadGeneration
        mutableState.value = mutableState.value.copy(isBusy = true, busyLabel = "正在打开文稿…")
        viewModelScope.launch(operationErrors) {
            val result = runOperationCatching {
                withContext(Dispatchers.IO) {
                    val loaded = repository.load(id) ?: return@withContext null
                    val bookmarks = repository.bookmarks(id)
                    val audio = repository.audioAssets(id)
                    Triple(loaded, bookmarks, audio)
                }
            }
            if (request != loadGeneration) return@launch
            result.onSuccess { data ->
                if (data == null) {
                    mutableState.value = mutableState.value.copy(isBusy = false, message = "没有找到这篇讲解词")
                    return@onSuccess
                }
                val (loaded, bookmarks, audio) = data
                val actualVersion = withContext(Dispatchers.IO) { repository.contentVersion(id) }
                if (request != loadGeneration) return@onSuccess
                if (actualVersion != loaded.contentVersion) { loadDocument(id); return@onSuccess }
                openLoadedDocument(loaded, bookmarks, audio)
            }.onFailure { error ->
                showFailure(UserOperation.OPEN, error)
            }
        }
    }

    private fun openLoadedDocument(
        loaded: LoadedDocument,
        bookmarks: List<ReaderBookmark>,
        audio: List<SentenceAudio>,
    ) {
        val document = loaded.document
        val content = loaded.content
        if (content.sentences.isEmpty()) {
            mutableState.value = mutableState.value.copy(isBusy = false, message = "这篇文稿没有可朗读的文字")
            return
        }
        val session = PlaybackBus.snapshot.value
        val same = session.documentId == document.id
        val start = (if (same) session.currentIndex else document.lastSentence).coerceIn(0, content.sentences.lastIndex)
        currentAudio = audio
        viewedVersion = loaded.contentVersion
        mutableState.value = mutableState.value.copy(
            page = AppPage.READER, currentDocument = document, content = content, readerLayout = loaded.display, bookmarks = bookmarks,
            loopMode = if (same) session.loopMode else LoopMode.NONE,
            rangeStart = if (same) session.rangeStart else start,
            rangeEnd = if (same) session.rangeEnd else start,
            rangeSelectionStage = null, viewedIndex = start, isBusy = false)
        if (same) PlaybackController.send(getApplication(), PlaybackCommand.Refresh(queueFor(loaded, audio)))
    }

    fun goHome() {
        stopPreviews()
        ++loadGeneration
        cancelRangeSelection()
        mutableState.value = mutableState.value.copy(page = AppPage.HOME)
        viewModelScope.launch(operationErrors) { refreshDocuments() }
    }

    fun openSettings() {
        stopPreviews()
        settingsReturnPage = mutableState.value.page
        mutableState.value = mutableState.value.copy(page = AppPage.SETTINGS)
        loadVoices()
    }

    fun closeSettings() {
        stopPreviews()
        mutableState.value = mutableState.value.copy(page = settingsReturnPage)
    }

    fun openEditor(document: ReaderDocument) {
        stopPreviews()
        editorReturnPage = mutableState.value.page
        val request = ++loadGeneration
        editor.open(document.id, onFailure = { message ->
            if (request == loadGeneration) showMessage(message)
        }) { if (request == loadGeneration) mutableState.value = mutableState.value.copy(page = AppPage.EDITOR) }
    }
    fun closeEditor() { mutableState.value = mutableState.value.copy(page = editorReturnPage) }

    fun renameDocument(id: String, title: String) {
        viewModelScope.launch(operationErrors) {
            withContext(Dispatchers.IO) { repository.rename(id, title) }
            if (PlaybackBus.snapshot.value.documentId == id) {
                val loaded = withContext(Dispatchers.IO) { repository.load(id) }
                val audio = withContext(Dispatchers.IO) { repository.audioAssets(id) }
                if (loaded != null) PlaybackController.send(getApplication(), PlaybackCommand.Refresh(queueFor(loaded, audio)))
            }
            refreshDocuments()
        }
    }

    fun deleteDocument(id: String) {
        val document = mutableState.value.documents.firstOrNull { it.id == id }
        if (PlaybackBus.snapshot.value.documentId == id) {
            TtsPlaybackService.dispatch(getApplication(), TtsPlaybackService.ACTION_STOP)
            PlaybackController.send(getApplication(), PlaybackCommand.Clear)
        }
        viewModelScope.launch(operationErrors) {
            withContext(Dispatchers.IO) { repository.trash(id) }
            refreshDocuments()
            mutableState.value = mutableState.value.copy(recentlyDeleted = document)
        }
    }

    fun undoDelete() {
        val document = mutableState.value.recentlyDeleted ?: return
        viewModelScope.launch(operationErrors) {
            withContext(Dispatchers.IO) { repository.restore(document.id) }
            refreshDocuments()
            mutableState.value = mutableState.value.copy(recentlyDeleted = null)
        }
    }

    fun clearUndoDelete() { mutableState.value = mutableState.value.copy(recentlyDeleted = null) }

    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch(operationErrors) {
            withContext(Dispatchers.IO) { repository.setPinned(id, pinned) }
            refreshDocuments()
        }
    }

    fun toggleBookmark(index: Int) {
        val ui = mutableState.value
        val document = ui.currentDocument ?: return
        val sentence = ui.content.sentences.getOrNull(index) ?: return
        viewModelScope.launch(operationErrors) {
            val added = withContext(Dispatchers.IO) { repository.toggleBookmark(document.id, sentence.id) }
            val bookmarks = withContext(Dispatchers.IO) { repository.bookmarks(document.id) }
            if (mutableState.value.currentDocument?.id != document.id) return@launch
            mutableState.value = mutableState.value.copy(bookmarks = bookmarks, message = if (added) "已加入重点句" else "已移除重点句")
        }
    }

    fun openLibrary() {
        stopPreviews()
        viewModelScope.launch(operationErrors) {
            val playlists = withContext(Dispatchers.IO) { repository.playlists() }
            mutableState.value = mutableState.value.copy(page = AppPage.LIBRARY, playlists = playlists)
        }
    }

    fun selectRoute(id: String?) {
        mutableState.value = mutableState.value.copy(routeContextId = id)
    }
    fun openRouteDocument(routeId: String, documentId: String) {
        val route = mutableState.value.playlists.firstOrNull { it.id == routeId } ?: return
        if (documentId !in route.documentIds || mutableState.value.documents.none { it.id == documentId })
            return showMessage("这篇文稿暂不可用，请选择其他站")
        mutableState.value = mutableState.value.copy(routeContextId = routeId)
        val request = ++loadGeneration
        viewModelScope.launch(operationErrors) {
            withContext(Dispatchers.IO) { repository.savePlaylistPosition(routeId, documentId) }
            val playlists = withContext(Dispatchers.IO) { repository.playlists() }
            mutableState.value = mutableState.value.copy(playlists = playlists)
            if (request == loadGeneration) loadDocument(documentId)
        }
    }
    fun moveRoute(delta: Int) {
        val ui = mutableState.value
        val route = ui.playlists.firstOrNull { it.id == ui.routeContextId } ?: return
        val at = route.documentIds.indexOf(ui.currentDocument?.id)
        val next = if (delta > 0) route.documentIds.drop(at + 1) else route.documentIds.take(at.coerceAtLeast(0)).reversed()
        val available = ui.documents.mapTo(hashSetOf()) { it.id }
        next.firstOrNull { it in available }?.let { openRouteDocument(route.id, it) }
            ?: showMessage(if (delta > 0) "已经是最后一个可用站点" else "已经是第一个可用站点")
    }

    fun closeLibrary() {
        mutableState.value = if (mutableState.value.routeContextId != null) mutableState.value.copy(routeContextId = null)
            else mutableState.value.copy(page = AppPage.HOME)
    }

    fun openCloudVoice() {
        stopPreviews()
        refreshCloudState()
        mutableState.value = mutableState.value.copy(page = AppPage.CLOUD_VOICE)
    }

    fun closeCloudVoice() {
        stopPreviews()
        mutableState.value = mutableState.value.copy(page = AppPage.SETTINGS)
    }

    fun openRecording() {
        stopPreviews()
        val ui = mutableState.value
        if (ui.currentDocument == null || ui.content.sentences.isEmpty()) return showMessage("请先打开一篇文稿")
        val index = if (PlaybackBus.snapshot.value.documentId == ui.currentDocument.id)
            PlaybackBus.snapshot.value.currentIndex else ui.viewedIndex
        mutableState.value = ui.copy(page = AppPage.RECORDING, recording = ui.recording.copy(
            currentIndex = index.coerceIn(0, ui.content.sentences.lastIndex)))
        viewModelScope.launch(operationErrors) {
            refreshRecordingState(index)
            val pending = recordingManager.pendingRecordings().firstOrNull { it.documentId == ui.currentDocument.id }
            if (mutableState.value.currentDocument?.id == ui.currentDocument.id)
                mutableState.value = mutableState.value.copy(recording = mutableState.value.recording.copy(pending = pending))
        }
    }

    fun closeRecording() {
        val r = mutableState.value.recording
        if (r.processing) return showMessage("正在整理录音，请稍候")
        if (r.isRecording || r.pending != null) {
            mutableState.value = mutableState.value.copy(recording = r.copy(exitConfirm = true)); return
        }
        stopPreviews()
        mutableState.value = mutableState.value.copy(page = AppPage.READER)
    }
    fun cancelRecordingExit() {
        mutableState.value = mutableState.value.copy(recording = mutableState.value.recording.copy(exitConfirm = false))
    }
    fun confirmRecordingExit(save: Boolean) {
        cancelRecordingExit()
        if (mutableState.value.recording.isRecording) stopRecording(save, exitAfter = true, saveNow = save)
        else if (save) savePendingRecording(exitAfter = true) else discardPendingRecording(exitAfter = true)
    }

    fun selectRecordingSentence(index: Int) {
        val ui = mutableState.value
        if (ui.recording.isRecording || ui.recording.processing || ui.recording.pending != null) return
        stopPreviews()
        mutableState.value = ui.copy(recording = ui.recording.copy(currentIndex = index.coerceIn(0, ui.content.sentences.lastIndex)))
    }

    fun recordingPermissionDenied() {
        mutableState.value = mutableState.value.copy(recording = mutableState.value.recording.copy(permissionDenied = true))
        showMessage("未获得麦克风权限，可在录音页打开应用权限设置后重试")
    }
    fun startRecording(index: Int) {
        val ui = mutableState.value
        if (ui.recording.processing || ui.recording.pending != null || ui.recording.isRecording) return
        val document = ui.currentDocument ?: return
        val sentence = ui.content.sentences.getOrNull(index) ?: return
        PlaybackController.send(getApplication(), PlaybackCommand.Pause)
        stopPreviews()
        PlaybackController.setCapturing(getApplication(), true)
        runOperationCatching { recordingManager.start(document.id, sentence.id, sentence.text) }.onSuccess {
            mutableState.value = mutableState.value.copy(recording = mutableState.value.recording.copy(
                currentIndex = index, isRecording = true, amplitude = 0f, permissionDenied = false))
            amplitudeJob?.cancel()
            amplitudeJob = viewModelScope.launch(operationErrors) {
                while (true) {
                    delay(100)
                    mutableState.value = mutableState.value.copy(recording = mutableState.value.recording.copy(
                        amplitude = (recordingManager.maxAmplitude() / 12_000f).coerceIn(0f, 1f)))
                }
            }
        }.onFailure { PlaybackController.setCapturing(getApplication(), false); showFailure(UserOperation.RECORD_START, it) }
    }

    fun stopRecording(save: Boolean = true, exitAfter: Boolean = false, saveNow: Boolean = false) {
        if (!mutableState.value.recording.isRecording) return
        amplitudeJob?.cancel(); amplitudeJob = null
        mutableState.value = mutableState.value.copy(recording = mutableState.value.recording.copy(
            isRecording = false, amplitude = 0f, processing = true))
        viewModelScope.launch(operationErrors) {
            try {
                val pending = recordingManager.endCapture()
                PlaybackController.setCapturing(getApplication(), false)
                mutableState.value = mutableState.value.copy(recording = mutableState.value.recording.copy(
                    processing = false, pending = pending))
                if (!save && pending != null) discardPendingRecording(exitAfter)
                else if (saveNow && pending != null) savePendingRecording(exitAfter)
                else showMessage(if (pending?.playable == true) "录音已保留待确认，请试听后保存" else "录音暂不可播放，原录音保留；本次文件仍可待处理")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.value = mutableState.value.copy(recording = mutableState.value.recording.copy(processing = false))
                showFailure(UserOperation.RECORD_PROCESS, error)
            } finally { PlaybackController.setCapturing(getApplication(), false) }
        }
    }

    fun savePendingRecording(exitAfter: Boolean = false) {
        val pending = mutableState.value.recording.pending ?: return
        if (mutableState.value.recording.processing) return
        stopPreviews()
        mutableState.value = mutableState.value.copy(recording = mutableState.value.recording.copy(processing = true))
        viewModelScope.launch(operationErrors) {
            try {
                recordingManager.save(pending)
                mutableState.value = mutableState.value.copy(recording = mutableState.value.recording.copy(
                    pending = null, processing = false))
                refreshAudioForDocument(pending.documentId)
                if (exitAfter) closeRecording()
                showMessage("本人录音已保存，上一份仍保留在旧音频中")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.value = mutableState.value.copy(recording = mutableState.value.recording.copy(processing = false))
                showFailure(UserOperation.RECORD_SAVE, error)
            }
        }
    }
    fun discardPendingRecording(exitAfter: Boolean = false) {
        val pending = mutableState.value.recording.pending ?: return
        if (mutableState.value.recording.processing) return
        stopPreviews()
        mutableState.value = mutableState.value.copy(recording = mutableState.value.recording.copy(processing = true))
        viewModelScope.launch(operationErrors) {
            try {
                recordingManager.discard(pending)
                mutableState.value = mutableState.value.copy(recording = mutableState.value.recording.copy(pending = null, processing = false))
                if (exitAfter) closeRecording()
                showMessage("已放弃本次录音，原有效录音保留")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.value = mutableState.value.copy(recording = mutableState.value.recording.copy(processing = false))
                showFailure(UserOperation.RECORD_DISCARD, error)
            }
        }
    }

    fun previewPendingRecording() {
        mutableState.value.recording.pending?.takeIf { it.playable }?.let { playPreviewFile(java.io.File(it.path)) }
    }
    fun previewRecording(index: Int) {
        if (mutableState.value.recording.isRecording || mutableState.value.recording.processing) return
        val sentence = mutableState.value.content.sentences.getOrNull(index) ?: return
        viewModelScope.launch(operationErrors) {
            val audio = withContext(Dispatchers.IO) { repository.latestAudio(sentence.id, AudioKind.RECORDING) }
            if (audio == null || audio.textHash != DocumentRepository.textHash(sentence.text)) return@launch showMessage("本句没有有效的本人录音")
            playPreviewFile(java.io.File(audio.path))
        }
    }
    private fun playPreviewFile(file: java.io.File) {
        if (PlaybackController.capturing) return showMessage("请先结束录音，再试听")
        PlaybackController.send(getApplication(), PlaybackCommand.Pause)
        stopPreviews()
        val token = previewGeneration
        runOperationCatching {
            previewPlayer = MediaPlayer().apply {
                setDataSource(com.museum.reader.data.AudioFiles.requireOwned(getApplication(), file).path)
                setOnPreparedListener { if (token == previewGeneration) it.start() else it.release() }
                setOnCompletionListener { it.release(); if (previewPlayer === it) previewPlayer = null }
                setOnErrorListener { p, _, _ -> p.release(); if (previewPlayer === p) previewPlayer = null
                    showMessage("这份录音不可播放，请在存储维护中检查"); true }
                prepareAsync()
            }
        }.onFailure { stopPreviews(); showMessage("无法试听，请检查录音文件") }
    }

    fun deleteRecording(index: Int) {
        if (mutableState.value.recording.isRecording || mutableState.value.recording.processing) return
        val document = mutableState.value.currentDocument ?: return
        val sentence = mutableState.value.content.sentences.getOrNull(index) ?: return
        stopPreviews()
        viewModelScope.launch(operationErrors) {
            withContext(Dispatchers.IO) { repository.latestAudio(sentence.id, AudioKind.RECORDING)?.let { repository.archiveAudio(it.id) } }
            refreshAudioForDocument(document.id)
            showMessage("已移入旧音频，可在存储维护中恢复")
        }
    }

    fun clearCurrentAudio(kind: AudioKind) {
        if (mutableState.value.recording.isRecording || mutableState.value.recording.processing) return
        val document = mutableState.value.currentDocument ?: return
        stopPreviews()
        PlaybackController.send(getApplication(), PlaybackCommand.Stop)
        viewModelScope.launch(operationErrors) {
            try {
                withContext(Dispatchers.IO) { repository.audioAssets(document.id).filter { it.kind == kind && it.archivedAt == null }
                    .forEach { repository.archiveAudio(it.id) } }
                refreshAudioForDocument(document.id)
                showMessage("已停用并保留旧音频。永久清理请进入存储维护")
            } catch (error: Exception) { showFailure(UserOperation.AUDIO, error) }
        }
    }

    fun openStorage() {
        stopPreviews()
        mutableState.value = mutableState.value.copy(page = AppPage.STORAGE)
        refreshStorage()
    }
    fun closeStorage() {
        stopPreviews()
        mutableState.value = mutableState.value.copy(page = AppPage.SETTINGS)
    }
    fun refreshStorage() {
        mutableState.value = mutableState.value.copy(isBusy = true, busyLabel = "正在核对存储和恢复区…")
        viewModelScope.launch(operationErrors) {
            try {
                withContext(Dispatchers.IO) { storageMaintenance.recover() }
                val overview = withContext(Dispatchers.IO) { storageMaintenance.overview() }
                val pending = recordingManager.pendingRecordings()
                mutableState.value = mutableState.value.copy(storage = overview, pendingRecordings = pending, isBusy = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showFailure(UserOperation.STORAGE, error) }
        }
    }
    fun restoreDeletedDocument(id: String) {
        viewModelScope.launch(operationErrors) {
            withContext(Dispatchers.IO) { repository.restore(id) }
            refreshDocuments()
            mutableState.value = mutableState.value.copy(playlists = withContext(Dispatchers.IO) { repository.playlists() })
            refreshStorage()
            showMessage("文稿已从最近删除恢复")
        }
    }
    fun processStorageAction(action: StorageAction) {
        if (mutableState.value.recording.isRecording || mutableState.value.recording.processing)
            return showMessage("请先结束并确认录音")
        cancelCloudGeneration()
        stopPreviews()
        PlaybackController.setMaintenance(getApplication(), true)
        mutableState.value = mutableState.value.copy(isBusy = true, busyLabel = "正在处理存储…")
        viewModelScope.launch(operationErrors) {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (action) {
                        is StorageAction.Cloud -> storageMaintenance.cleanupAudio(action.audio, cloudOnly = true)
                        is StorageAction.Old -> storageMaintenance.cleanupAudio(listOf(action.item.audio))
                        is StorageAction.Unlinked -> storageMaintenance.cleanupUnlinked(action.item)
                        is StorageAction.Purge -> storageMaintenance.purge(action.item)
                    }
                }
                refreshDocuments()
                mutableState.value = mutableState.value.copy(playlists = withContext(Dispatchers.IO) { repository.playlists() })
                showMessage("处理了 ${result.removedReferences} 条音频关联，清理 ${result.deletedFiles} 个文件，${result.pendingFiles} 个待重试")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showFailure(UserOperation.STORAGE, error) }
            finally {
                PlaybackController.setMaintenance(getApplication(), false)
                mutableState.value = mutableState.value.copy(isBusy = false)
                if (currentCoroutineContext().isActive) {
                    val session = PlaybackBus.snapshot.value
                    val loaded = session.documentId?.let { withContext(Dispatchers.IO) { repository.load(it) } }
                    if (loaded == null) PlaybackController.send(getApplication(), PlaybackCommand.Clear)
                    else PlaybackController.send(getApplication(), PlaybackCommand.Refresh(queueFor(loaded,
                        withContext(Dispatchers.IO) { repository.audioAssets(loaded.document.id) })))
                    refreshStorage()
                }
            }
        }
    }
    fun restoreStoredAudio(id: String) {
        stopPreviews()
        PlaybackController.send(getApplication(), PlaybackCommand.Stop)
        viewModelScope.launch(operationErrors) {
            try {
                withContext(Dispatchers.IO) { storageMaintenance.restoreAudio(id) }
                mutableState.value.currentDocument?.id?.let { refreshAudioForDocument(it) }
                refreshStorage()
                showMessage("旧音频已恢复，上一份有效音频仍保留")
            } catch (error: Exception) { showFailure(UserOperation.AUDIO, error) }
        }
    }
    fun stageAudioExport(path: String) { pendingAudioExport = path }
    fun exportSelectedAudio(uri: Uri) {
        val path = pendingAudioExport ?: return
        pendingAudioExport = null
        mutableState.value = mutableState.value.copy(isBusy = true, busyLabel = "正在另存原音频…")
        viewModelScope.launch(operationErrors) {
            try {
                withContext(Dispatchers.IO) {
                    val file = com.museum.reader.data.AudioFiles.requireOwned(getApplication(), java.io.File(path))
                    require(file.isFile) { "原音频已缺失，请刷新检查" }
                    getApplication<Application>().contentResolver.openOutputStream(uri, "w")?.buffered()?.use { target ->
                        file.inputStream().buffered().use { source -> source.copyTo(target) }
                    } ?: error("无法写入所选位置，请重新选择")
                }
                showMessage("原音频副本已另存，本机原文件保留")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showFailure(UserOperation.AUDIO, error) }
            finally { mutableState.value = mutableState.value.copy(isBusy = false) }
        }
    }

    fun previewStoredFile(path: String) { playPreviewFile(java.io.File(path)) }
    fun processPendingFromStorage(pending: PendingRecording, save: Boolean) {
        stopPreviews()
        viewModelScope.launch(operationErrors) {
            try {
                if (save) recordingManager.save(pending) else recordingManager.discard(pending)
                mutableState.value.currentDocument?.id?.let { refreshAudioForDocument(it) }
                refreshStorage()
                showMessage(if (save) "待确认录音已保存" else "本次待确认录音已放弃，原有效录音保留")
            } catch (error: Exception) { showFailure(UserOperation.RECORD_PROCESS, error) }
        }
    }

    fun onAppBackground() {
        editor.flush()
        stopPreviews()
        if (mutableState.value.recording.isRecording) stopRecording(save = true)
    }

    fun updateCloudPreferences(value: CloudSpeechPreferences) {
        cloudPreferences.save(value)
        refreshCloudState()
        publishDefaults()
    }

    fun saveXunfeiCredentials(appId: String, apiKey: String, apiSecret: String) {
        saveCredentials { credentialStore.saveXunfei(XunfeiCredentials(appId.trim(), apiKey.trim(), apiSecret.trim())) }
    }

    fun saveBaiduCredentials(appId: String, apiKey: String, secretKey: String) {
        saveCredentials { credentialStore.saveBaidu(BaiduCredentials(appId.trim(), apiKey.trim(), secretKey.trim())) }
    }

    fun clearCloudCredentials(provider: CloudProvider) {
        viewModelScope.launch(operationErrors) {
            withContext(Dispatchers.IO) { credentialStore.clear(provider) }
            refreshCloudState()
            showMessage("已清除${provider.label}密钥")
        }
    }

    fun previewCloudVoice(provider: CloudProvider, voiceId: String) {
        if (mutableState.value.recording.isRecording) return showMessage("请先结束录音")
        PlaybackController.send(getApplication(), PlaybackCommand.Pause)
        stopPreviews()
        val token = previewGeneration
        showMessage("正在准备试听…")
        previewJob = viewModelScope.launch(operationErrors) {
            try {
                val file = withContext(Dispatchers.IO) {
                    cloudSpeechManager.synthesizePreview(provider, voiceId, "你好，欢迎使用随身听。愿你今天的讲解从容、清晰。")
                }
                currentCoroutineContext().ensureActive()
                if (token != previewGeneration || mutableState.value.page != AppPage.CLOUD_VOICE) return@launch
                previewPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { player -> player.release(); if (previewPlayer === player) previewPlayer = null }
                    setOnErrorListener { player, _, _ ->
                        player.release(); if (previewPlayer === player) previewPlayer = null
                        showMessage("试听音频不可播放，请检查声音配置"); true
                    }
                    prepareAsync()
                    setOnPreparedListener { player -> if (token == previewGeneration) player.start() else player.release() }
                }
                refreshCloudState()
                showMessage("正在试听${provider.label}声音")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) { if (token == previewGeneration) showFailure(UserOperation.CLOUD, error) }
        }
    }

    fun generateCloudAudioForCurrentDocument() {
        val ui = mutableState.value
        val document = ui.currentDocument ?: return showMessage("请先打开一篇文稿")
        if (ui.content.sentences.isEmpty()) return showMessage("当前文稿没有可合成的句子")
        cloudGenerationJob?.cancel()
        val task = ++cloudGeneration
        val version = viewedVersion
        val sentences = ui.content.sentences
        mutableState.value = ui.copy(cloudSpeech = ui.cloudSpeech.copy(
            generationDone = 0, generationTotal = sentences.size, generationDocumentTitle = document.title),
            message = "开始生成云声音，已完成的有效缓存会复用")
        cloudGenerationJob = viewModelScope.launch(operationErrors) {
            var completed = 0
            try {
                for (sentence in sentences) {
                    currentCoroutineContext().ensureActive()
                    withContext(Dispatchers.IO) { cloudSpeechManager.synthesizeSentence(document.id, sentence.id, sentence.text, version) }
                    if (task != cloudGeneration) return@launch
                    completed++
                    mutableState.value = mutableState.value.copy(cloudSpeech = mutableState.value.cloudSpeech.copy(
                        generationDone = completed, usedToday = cloudPreferences.usedToday()))
                }
                showMessage("“${document.title}”已完成 $completed 句云声音，可离线使用")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (task == cloudGeneration) showFailure(UserOperation.CLOUD, error, "已完成 $completed/${sentences.size} 句；")
            } finally {
                if (task == cloudGeneration) {
                    mutableState.value = mutableState.value.copy(
                        cloudSpeech = mutableState.value.cloudSpeech.copy(generationDone = completed, generationTotal = 0))
                    // Cancellation must never continue database reads or start fallback requests.
                    if (currentCoroutineContext().isActive) {
                        val loaded = withContext(Dispatchers.IO) { repository.load(document.id) }
                        val assets = withContext(Dispatchers.IO) { repository.audioAssets(document.id) }
                        if (loaded != null) {
                            PlaybackController.send(getApplication(), PlaybackCommand.Refresh(queueFor(loaded, assets)))
                            if (mutableState.value.currentDocument?.id == document.id && viewedVersion == version)
                                currentAudio = assets
                        }
                    }
                }
            }
        }
    }

    fun cancelCloudGeneration() {
        ++cloudGeneration
        cloudGenerationJob?.cancel(); cloudGenerationJob = null
        mutableState.value = mutableState.value.copy(cloudSpeech = mutableState.value.cloudSpeech.copy(generationTotal = 0),
            message = "已停止本机后续生成，完成缓存保留；已提交请求的计费以服务商为准")
    }

    fun savePlaylist(id: String?, title: String, documentIds: List<String>) {
        viewModelScope.launch(operationErrors) {
            withContext(Dispatchers.IO) { repository.savePlaylist(id, title, documentIds) }
            val playlists = withContext(Dispatchers.IO) { repository.playlists() }
            mutableState.value = mutableState.value.copy(playlists = playlists, message = "讲解路线已保存")
        }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch(operationErrors) {
            withContext(Dispatchers.IO) { repository.deletePlaylist(id) }
            mutableState.value = mutableState.value.copy(playlists = withContext(Dispatchers.IO) { repository.playlists() })
        }
    }

    fun setStopTimer(minutes: Int?) {
        val deadline = minutes?.let { SleepDeadline.create(android.os.SystemClock.elapsedRealtime(), it) }
        PlaybackController.send(getApplication(), PlaybackCommand.Timer(deadline))
        showMessage(if (minutes == null) "定时停止已关闭" else "$minutes 分钟后停止朗读")
    }

    fun exportBackup(uri: Uri) {
        mutableState.value = mutableState.value.copy(isBusy = true, busyLabel = "正在核对并导出备份…")
        viewModelScope.launch(operationErrors) {
            runOperationCatching {
                withContext(Dispatchers.IO) { backupManager.export(uri, mutableState.value.settings) }
            }.onSuccess {
                mutableState.value = mutableState.value.copy(isBusy = false, message = "备份已保存")
            }.onFailure { error ->
                showFailure(UserOperation.BACKUP_EXPORT, error)
            }
        }
    }

    fun importBackup(uri: Uri) {
        mutableState.value = mutableState.value.copy(isBusy = true, busyLabel = "正在校验备份…")
        viewModelScope.launch(operationErrors) {
            runOperationCatching {
                withContext(Dispatchers.IO) { backupManager.prepare(uri) }
            }.onSuccess { prepared ->
                mutableState.value = mutableState.value.copy(isBusy = false, pendingBackup = prepared)
            }.onFailure { error ->
                showFailure(UserOperation.BACKUP_READ, error)
            }
        }
    }

    fun cancelBackup() {
        val pending = mutableState.value.pendingBackup ?: return
        mutableState.value = mutableState.value.copy(pendingBackup = null)
        viewModelScope.launch(operationErrors) {
            runOperationCatching { withContext(Dispatchers.IO) { backupManager.discard(pending) } }
                .onFailure { showMessage("暂存资料会在下次启动时整理") }
        }
    }

    fun confirmBackup(policy: BackupConflictPolicy, restoreSettings: Boolean) {
        val pending = mutableState.value.pendingBackup ?: return
        if (cloudGenerationJob?.isActive == true) cancelCloudGeneration()
        stopPreviews()
        PlaybackController.setMaintenance(getApplication(), true)
        PlaybackController.send(getApplication(), PlaybackCommand.Clear)
        mutableState.value = mutableState.value.copy(isBusy = true, pendingBackup = null, busyLabel = "正在恢复备份…")
        viewModelScope.launch(operationErrors) {
            try {
                val result = withContext(Dispatchers.IO) {
                    backupManager.restore(pending, policy, restoreSettings, acceptWarnings = true)
                }
                val documents = withContext(Dispatchers.IO) { repository.summaries() }
                val playlists = withContext(Dispatchers.IO) { repository.playlists() }
                currentAudio = emptyList()
                mutableState.value = mutableState.value.copy(
                    documents = documents, playlists = playlists, currentDocument = null,
                    content = SegmentedText("", emptyList()), readerLayout = null,
                    settings = settingsRepository.load(), bookmarks = emptyList(), versions = emptyList(),
                    backupWarnings = result.warnings, backupRecoveryPoint = result.recoveryPoint,
                    isBusy = false, message = "恢复已提交：文稿 " + result.documentCount + " 篇，音频 " + result.audioCount + " 条" +
                        if (result.warnings.isNotEmpty()) "，有未恢复音频，请查看详情" else "")
                refreshCloudState()
                publishDefaults()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showFailure(UserOperation.BACKUP_RESTORE, error) }
            finally { PlaybackController.setMaintenance(getApplication(), false) }
        }
    }

    fun dismissBackupResult() {
        mutableState.value = mutableState.value.copy(backupWarnings = emptyList(), backupRecoveryPoint = null)
    }

    fun selectSentence(index: Int) {
        val ui = mutableState.value
        val safe = index.coerceIn(0, ui.content.sentences.lastIndex.coerceAtLeast(0))
        when (ui.rangeSelectionStage) {
            RangeSelectionStage.START -> mutableState.value = ui.copy(rangeStart = safe, rangeEnd = safe,
                rangeSelectionStage = RangeSelectionStage.END)
            RangeSelectionStage.END -> {
                val range = PlaybackRules.normalizeRange(ui.rangeStart, safe, ui.content.sentences.size)
                mutableState.value = ui.copy(loopMode = LoopMode.RANGE, rangeStart = range.first,
                    rangeEnd = range.last, viewedIndex = range.first, rangeSelectionStage = null)
                if (PlaybackBus.snapshot.value.documentId == ui.currentDocument?.id) {
                    PlaybackController.send(getApplication(), PlaybackCommand.Mode(LoopMode.RANGE, range.first, range.last))
                    PlaybackController.send(getApplication(), PlaybackCommand.Seek(range.first, false))
                }
                rangeBefore = null
                showMessage("已选择第 ${range.first + 1}–${range.last + 1} 句，点播放开始")
            }
            null -> {
                val outside = ui.loopMode == LoopMode.RANGE && safe !in ui.rangeStart..ui.rangeEnd
                mutableState.value = ui.copy(viewedIndex = safe, loopMode = if (outside) LoopMode.NONE else ui.loopMode)
                if (PlaybackBus.snapshot.value.documentId == ui.currentDocument?.id)
                    PlaybackController.send(getApplication(), PlaybackCommand.Seek(safe))
                if (outside) showMessage("已退出原范围循环")
            }
        }
    }

    fun startAt(index: Int, fullText: Boolean = false) {
        if (fullText) setLoopMode(LoopMode.NONE)
        selectSentence(index)
        if (mutableState.value.rangeSelectionStage != null) return
        val session = PlaybackBus.snapshot.value
        if (session.documentId != mutableState.value.currentDocument?.id) loadPlayback(index, play = true)
        else PlaybackController.send(getApplication(), PlaybackCommand.Seek(index, true))
    }

    fun selectParagraph(paragraphIndex: Int) {
        val ui = mutableState.value
        ui.content.paragraphs.getOrNull(paragraphIndex)?.let { paragraph ->
            if (ui.rangeSelectionStage != null)
                selectSentence(if (ui.rangeSelectionStage == RangeSelectionStage.END) paragraph.sentences.last().index else paragraph.sentenceStart)
            else startAt(paragraph.sentenceStart)
        }
    }

    fun setLoopMode(mode: LoopMode) {
        val ui = mutableState.value
        if (mode == LoopMode.RANGE) {
            if (ui.rangeSelectionStage != null) return
            rangeBefore = Triple(ui.loopMode, ui.rangeStart, ui.rangeEnd)
            if (PlaybackBus.snapshot.value.documentId == ui.currentDocument?.id)
                PlaybackController.send(getApplication(), PlaybackCommand.Pause)
            mutableState.value = ui.copy(rangeSelectionStage = RangeSelectionStage.START)
        } else {
            rangeBefore = null
            mutableState.value = ui.copy(loopMode = mode, rangeSelectionStage = null)
            if (PlaybackBus.snapshot.value.documentId == ui.currentDocument?.id)
                PlaybackController.send(getApplication(), PlaybackCommand.Mode(mode, ui.rangeStart, ui.rangeEnd))
        }
    }

    fun cancelRangeSelection() {
        val old = rangeBefore
        if (old != null) mutableState.value = mutableState.value.copy(loopMode = old.first,
            rangeStart = old.second, rangeEnd = old.third, rangeSelectionStage = null)
        else mutableState.value = mutableState.value.copy(rangeSelectionStage = null)
        rangeBefore = null
    }

    fun playPause() {
        stopPreviews()
        val ui = mutableState.value
        val session = PlaybackBus.snapshot.value
        if (ui.page == AppPage.READER && session.documentId != ui.currentDocument?.id)
            loadPlayback(ui.viewedIndex, play = true)
        else PlaybackController.send(getApplication(), if (session.isPlaying) PlaybackCommand.Pause else PlaybackCommand.Play)
    }

    fun previous() = moveSelection(-1)
    fun next() = moveSelection(1)
    fun stop() = PlaybackController.send(getApplication(), PlaybackCommand.Stop)

    fun updateSpeechRate(value: Float) = saveSettings(mutableState.value.settings.copy(speechRate = value))
    fun updatePitch(value: Float) = saveSettings(mutableState.value.settings.copy(pitch = value))
    fun updateFontSize(value: Float) = saveSettings(mutableState.value.settings.copy(fontSizeSp = value))
    fun updateVoice(name: String?) = saveSettings(mutableState.value.settings.copy(voiceName = name))
    fun updateSentencePause(value: Int) = saveSettings(mutableState.value.settings.copy(sentencePauseMs = value))
    fun updateParagraphPause(value: Int) = saveSettings(mutableState.value.settings.copy(paragraphPauseMs = value))

    fun useCurrentSettingsForDocument() {
        val document = mutableState.value.currentDocument ?: return
        val settings = mutableState.value.settings
        val updated = document.copy(
            speechRate = settings.speechRate,
            sentencePauseMs = settings.sentencePauseMs,
            paragraphPauseMs = settings.paragraphPauseMs,
        )
        mutableState.value = mutableState.value.copy(currentDocument = updated)
        PlaybackController.send(getApplication(), PlaybackCommand.Refresh(
            queueFor(LoadedDocument(updated, mutableState.value.content, viewedVersion), currentAudio)))
        viewModelScope.launch(operationErrors) {
            withContext(Dispatchers.IO) {
                repository.saveDocumentSettings(document.id, settings.speechRate, settings.sentencePauseMs, settings.paragraphPauseMs)
            }
            refreshDocuments()
        }
        showMessage("已保存为这篇文稿的专用设置")
    }

    fun followGlobalForDocument() {
        val document = mutableState.value.currentDocument ?: return
        viewModelScope.launch(operationErrors) {
            withContext(Dispatchers.IO) { repository.saveDocumentSettings(document.id, null, null, null) }
            val loaded = withContext(Dispatchers.IO) { repository.load(document.id) } ?: return@launch
            val audio = withContext(Dispatchers.IO) { repository.audioAssets(document.id) }
            PlaybackController.send(getApplication(), PlaybackCommand.Refresh(queueFor(loaded, audio)))
            if (mutableState.value.currentDocument?.id == document.id)
                mutableState.value = mutableState.value.copy(currentDocument = loaded.document)
            showMessage("本文已恢复跟随全局默认，播放中从下一句起生效")
        }
    }

    fun testVoice() {
        if (PlaybackController.capturing) return showMessage("请先结束录音，再试听")
        if (PlaybackBus.snapshot.value.isPlaying) return showMessage("请先暂停当前文稿，再试听声音")
        stopPreviews()
        val token = previewGeneration
        voicePreview = TextToSpeech(getApplication()) { status ->
            viewModelScope.launch(operationErrors) {
                if (token != previewGeneration || mutableState.value.page != AppPage.SETTINGS) return@launch
                val engine = voicePreview ?: return@launch
                if (status != TextToSpeech.SUCCESS) return@launch showMessage("系统声音初始化失败，请检查系统设置")
                val language = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    mutableState.value = mutableState.value.copy(ttsStatus = TtsStatus.MISSING)
                    stopPreviews()
                    return@launch showMessage("手机尚未安装可用的中文声音")
                }
                val settings = mutableState.value.settings
                engine.setSpeechRate(settings.speechRate); engine.setPitch(settings.pitch)
                settings.voiceName?.let { name -> engine.voices?.firstOrNull { it.name == name }?.let { engine.voice = it } }
                engine.speak("你好，这里是随身听。祝你今天讲解顺利。", TextToSpeech.QUEUE_FLUSH, null, "voice-preview:$token")
            }
        }
    }

    fun consumeMessage() { mutableState.value = mutableState.value.copy(message = null) }

    private fun showFailure(operation: UserOperation, error: Throwable, prefix: String = "") {
        val message = prefix + operationMessage(error, operation)
        mutableState.value = mutableState.value.copy(isBusy = false, message = message,
            failure = OperationFailure(operation, message))
    }

    fun dismissFailure() { mutableState.value = mutableState.value.copy(failure = null, message = null) }

    private fun createDocument(title: String, text: String, source: DocumentSource) {
        mutableState.value = mutableState.value.copy(isBusy = true, busyLabel = "正在分句并保存文稿…")
        viewModelScope.launch(operationErrors) {
            runOperationCatching {
                withContext(Dispatchers.IO) { repository.create(title, text, source) }
            }.onSuccess { document ->
                refreshDocuments(); openDocument(document.id)
            }.onFailure { error -> showFailure(UserOperation.CREATE, error) }
        }
    }

    private fun queueFor(loaded: LoadedDocument, audio: List<SentenceAudio>): PlaybackSnapshot {
        val document = loaded.document
        val cloud = cloudPreferences.load()
        return PlaybackSnapshot(documentId = document.id, documentTitle = document.title,
            sentences = loaded.content.sentences.map { it.text },
            paragraphIndices = loaded.content.sentences.map { it.paragraphIndex },
            sentenceIds = loaded.content.sentences.map { it.id }, contentVersion = loaded.contentVersion,
            audioCandidates = audio.groupBy { it.sentenceId }, speechRate = document.speechRate,
            sentencePauseMs = document.sentencePauseMs, paragraphPauseMs = document.paragraphPauseMs,
            currentIndex = document.lastSentence, globalSettings = settingsRepository.load(),
            cloudEnabled = cloud.enabled, preferredCloudProvider = cloud.primaryProvider.name,
            preferredCloudVoice = if (cloud.primaryProvider == CloudProvider.XUNFEI) cloud.xunfeiVoice else cloud.baiduVoice)
    }

    private fun loadPlayback(startIndex: Int, play: Boolean = false) {
        val ui = mutableState.value
        val document = ui.currentDocument ?: return
        val queue = queueFor(LoadedDocument(document, ui.content, viewedVersion), currentAudio)
            .copy(currentIndex = startIndex, loopMode = ui.loopMode, rangeStart = ui.rangeStart, rangeEnd = ui.rangeEnd)
        PlaybackController.send(getApplication(), PlaybackCommand.Load(queue, play))
    }

    private fun moveSelection(delta: Int) {
        val ui = mutableState.value
        if (ui.page == AppPage.READER && PlaybackBus.snapshot.value.documentId != ui.currentDocument?.id) {
            val range = if (ui.loopMode == LoopMode.RANGE) ui.rangeStart..ui.rangeEnd
                else 0..ui.content.sentences.lastIndex.coerceAtLeast(0)
            mutableState.value = ui.copy(viewedIndex = (ui.viewedIndex + delta).coerceIn(range.first, range.last))
        } else PlaybackController.send(getApplication(), PlaybackCommand.Move(delta))
    }

    private fun publishDefaults() {
        val c = cloudPreferences.load()
        PlaybackController.send(getApplication(), PlaybackCommand.Defaults(mutableState.value.settings, c.enabled,
            c.primaryProvider.name, if (c.primaryProvider == CloudProvider.XUNFEI) c.xunfeiVoice else c.baiduVoice))
    }

    fun updateDocumentRate(rate: Float?) {
        val ui = mutableState.value
        val document = ui.currentDocument ?: return
        viewModelScope.launch(operationErrors) {
            withContext(Dispatchers.IO) {
                repository.saveDocumentSettings(document.id, rate, document.sentencePauseMs, document.paragraphPauseMs)
            }
            if (mutableState.value.currentDocument?.id != document.id) return@launch
            mutableState.value = mutableState.value.copy(currentDocument = document.copy(speechRate = rate))
            PlaybackController.send(getApplication(), PlaybackCommand.Refresh(
                queueFor(LoadedDocument(mutableState.value.currentDocument!!, ui.content, viewedVersion), currentAudio)))
            showMessage(if (PlaybackBus.snapshot.value.isPlaying) "本文倍率已保存，下一句起生效" else "本文倍率已保存")
        }
    }

    private fun stopPreviews() {
        ++previewGeneration
        previewJob?.cancel(); previewJob = null
        voicePreview?.stop(); voicePreview?.shutdown(); voicePreview = null
        previewPlayer?.release(); previewPlayer = null
    }

    private fun saveSettings(settings: ReaderSettings) {
        mutableState.value = mutableState.value.copy(settings = settings)
        settingsRepository.save(settings)
        publishDefaults()
    }

    private fun saveCredentials(block: () -> Unit) {
        mutableState.value = mutableState.value.copy(isBusy = true, busyLabel = "正在加密保存密钥…")
        viewModelScope.launch(operationErrors) {
            runOperationCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    refreshCloudState()
                    mutableState.value = mutableState.value.copy(isBusy = false, message = "密钥已加密保存在本机")
                }
                .onFailure { error ->
                    showFailure(UserOperation.CREDENTIALS, error)
                }
        }
    }

    private fun refreshCloudState() {
        val credentials = credentialStore.load()
        val previous = mutableState.value.cloudSpeech
        mutableState.value = mutableState.value.copy(
            cloudSpeech = previous.copy(
                preferences = cloudPreferences.load(),
                xunfeiConfigured = credentials.xunfei?.isComplete == true,
                baiduConfigured = credentials.baidu?.isComplete == true,
                usedToday = cloudPreferences.usedToday(),
                credentialError = credentialStore.lastReadError,
            ),
        )
    }

    private suspend fun refreshAudioForDocument(documentId: String) {
        val assets = withContext(Dispatchers.IO) { repository.audioAssets(documentId) }
        if (mutableState.value.currentDocument?.id != documentId) return
        currentAudio = assets
        val index = mutableState.value.recording.currentIndex
        val recordings = assets.filter { it.kind == AudioKind.RECORDING && it.archivedAt == null && java.io.File(it.path).isFile }
        val cloud = assets.filter { it.kind == AudioKind.CLOUD && it.archivedAt == null && java.io.File(it.path).isFile }
        mutableState.value = mutableState.value.copy(
            recording = mutableState.value.recording.copy(
                currentIndex = index,
                recordedSentenceIds = recordings.mapTo(hashSetOf()) { it.sentenceId },
                recordingBytes = recordings.sumOf { java.io.File(it.path).length() },
                cloudBytes = cloud.sumOf { java.io.File(it.path).length() },
            ),
        )
        if (PlaybackBus.snapshot.value.documentId == documentId) {
            PlaybackController.send(getApplication(), PlaybackCommand.Refresh(queueFor(
                LoadedDocument(mutableState.value.currentDocument!!, mutableState.value.content, viewedVersion), assets)))
        }
    }

    private suspend fun refreshRecordingState(index: Int) {
        val documentId = mutableState.value.currentDocument?.id ?: return
        refreshAudioForDocument(documentId)
        mutableState.value = mutableState.value.copy(recording = mutableState.value.recording.copy(currentIndex = index))
    }


    private suspend fun refreshDocuments() {
        val documents = withContext(Dispatchers.IO) { repository.summaries() }
        mutableState.value = mutableState.value.copy(documents = documents)
    }

    private fun showMessage(message: String) { mutableState.value = mutableState.value.copy(message = message) }

    private fun loadVoices() {
        if (mutableState.value.voiceChoices.isNotEmpty() || voiceLoader != null) return
        mutableState.value = mutableState.value.copy(ttsStatus = TtsStatus.LOADING)
        voiceLoader = TextToSpeech(getApplication()) { status ->
            val engine = voiceLoader ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                val language = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                val available = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED
                val choices = engine.voices.orEmpty()
                    .filter { it.locale.language == Locale.CHINESE.language }
                    .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
                    .map { voice ->
                        val region = voice.locale.getDisplayCountry(Locale.SIMPLIFIED_CHINESE)
                        VoiceChoice(voice.name, buildString {
                            append(if (region.isBlank()) "中文声音" else "中文 · $region")
                            append(if (voice.isNetworkConnectionRequired) "（需网络）" else "（离线）")
                        })
                    }.distinctBy { it.name }
                mutableState.value = mutableState.value.copy(voiceChoices = choices, ttsStatus = if (available) TtsStatus.READY else TtsStatus.MISSING)
            } else mutableState.value = mutableState.value.copy(ttsStatus = TtsStatus.ERROR)
            engine.shutdown(); voiceLoader = null
        }
    }

    private fun defaultClipboardTitle(): String = "剪贴板讲解词 · " +
        SimpleDateFormat("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date())

    override fun onCleared() {
        stopPreviews()
        previewJob?.cancel(); cloudGenerationJob?.cancel(); amplitudeJob?.cancel(); recordingManager.preserveImmediately()
        PlaybackController.setCapturing(getApplication(), false)
        voiceLoader?.shutdown(); voicePreview?.shutdown(); previewPlayer?.release()
        super.onCleared()
    }
}
