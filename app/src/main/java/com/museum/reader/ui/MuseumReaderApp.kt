@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.museum.reader.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museum.reader.model.DocumentSource
import com.museum.reader.model.LoopMode
import com.museum.reader.model.PlaybackSnapshot
import com.museum.reader.model.ReaderDocument
import com.museum.reader.model.AudioKind
import com.museum.reader.data.ImportedDocument
import com.museum.reader.data.BackupManager
import com.museum.reader.domain.TextSegmenter
import com.museum.reader.playback.PlaybackBus
import com.museum.reader.ui.theme.MuseumReaderTheme
import com.museum.reader.ui.theme.prominentCardSubtitleColor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MuseumReaderApp(viewModel: MuseumReaderViewModel) {
    MuseumReaderTheme {
        val ui by viewModel.state.collectAsStateWithLifecycle()
        val playback by PlaybackBus.snapshot.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        val context = LocalContext.current
        var pendingPlay by remember { mutableStateOf(false) }
        var pendingRecordingIndex by remember { mutableStateOf<Int?>(null) }

        BackHandler(enabled = ui.page != AppPage.HOME && ui.page != AppPage.EDITOR) {
            when (ui.page) {
                AppPage.READER -> viewModel.goHome()
                AppPage.SETTINGS -> viewModel.closeSettings()
                AppPage.EDITOR -> viewModel.closeEditor()
                AppPage.LIBRARY -> viewModel.closeLibrary()
                AppPage.CLOUD_VOICE -> viewModel.closeCloudVoice()
                AppPage.RECORDING -> viewModel.closeRecording()
                AppPage.STORAGE -> viewModel.closeStorage()
                AppPage.HOME -> Unit
            }
        }

        BackHandler(enabled = ui.isBusy) { }
        val notificationPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            if (pendingPlay) {
                pendingPlay = false
                viewModel.playPause()
            }
        }
        val filePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri -> uri?.let(viewModel::importFile) }
        val recordingPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val index = pendingRecordingIndex
            pendingRecordingIndex = null
            if (granted && index != null) viewModel.startRecording(index)
            else if (!granted) viewModel.recordingPermissionDenied()
        }
        val audioExporter = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri -> uri?.let(viewModel::exportSelectedAudio) }
        val backupExporter = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE),
        ) { uri -> uri?.let(viewModel::exportBackup) }
        val backupImporter = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri -> uri?.let(viewModel::importBackup) }

        LaunchedEffect(ui.message, ui.failure) {
            ui.message?.let {
                if (ui.failure != null) return@LaunchedEffect
                snackbar.showSnackbar(it)
                viewModel.consumeMessage()
            }
        }
        LaunchedEffect(playback.error) {
            playback.error?.let { snackbar.showSnackbar(it) }
        }
        LaunchedEffect(ui.recentlyDeleted?.id) {
            val deleted = ui.recentlyDeleted ?: return@LaunchedEffect
            val result = snackbar.showSnackbar(
                message = "已删除“${deleted.title}”",
                actionLabel = "撤销",
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.clearUndoDelete()
        }

        val requestPlay: () -> Unit = {
            if (
                Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingPlay = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.playPause()
            }
        }

        val pages = rememberSaveableStateHolder()
        Box(Modifier.fillMaxSize()) {
            pages.SaveableStateProvider(if (ui.page == AppPage.READER) "reader:" + ui.currentDocument?.id else ui.page.name) {
            when (ui.page) {
                AppPage.HOME -> HomeScreen(
                    ui = ui,
                    playback = playback,
                    onPaste = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        viewModel.createFromClipboard(text)
                    },
                    onImport = { filePicker.launch(arrayOf("text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword")) },
                    onOpen = viewModel::openDocument,
                    onRename = viewModel::renameDocument,
                    onEdit = viewModel::openEditor,
                    onDelete = viewModel::deleteDocument,
                    onPin = viewModel::setPinned,
                    onSettings = viewModel::openSettings,
                    onLibrary = viewModel::openLibrary,
                    onPlayPause = requestPlay,
                    onPrevious = viewModel::previous,
                    onNext = viewModel::next,
                    snackbar = snackbar,
                )
                AppPage.READER -> ReaderScreen(
                    ui = ui,
                    playback = playback,
                    onBack = viewModel::goHome,
                    onSettings = viewModel::openSettings,
                    onEdit = { ui.currentDocument?.let(viewModel::openEditor) },
                    onSentence = viewModel::selectSentence,
                    onParagraph = viewModel::selectParagraph,
                    onPlayPause = requestPlay,
                    onPrevious = viewModel::previous,
                    onNext = viewModel::next,
                    onStop = viewModel::stop,
                    onLoopMode = viewModel::setLoopMode,
                    onCancelRange = viewModel::cancelRangeSelection,
                    onBookmark = viewModel::toggleBookmark,
                    onRecording = viewModel::openRecording,
                    onStartAt = { viewModel.startAt(it) },
                    onReadAll = { viewModel.startAt(0, fullText = true) },
                    onRate = viewModel::updateDocumentRate,
                    onTimer = viewModel::setStopTimer,
                    onRouteMove = viewModel::moveRoute,
                    snackbar = snackbar,
                )
                AppPage.SETTINGS -> SettingsScreen(
                    ui = ui,
                    onBack = viewModel::closeSettings,
                    onSpeechRate = viewModel::updateSpeechRate,
                    onPitch = viewModel::updatePitch,
                    onFontSize = viewModel::updateFontSize,
                    onVoice = viewModel::updateVoice,
                    onTest = viewModel::testVoice,
                    onSentencePause = viewModel::updateSentencePause,
                    onParagraphPause = viewModel::updateParagraphPause,
                    onSaveForDocument = viewModel::useCurrentSettingsForDocument,
                    onCloudVoice = viewModel::openCloudVoice,
                    onFollowGlobal = viewModel::followGlobalForDocument,
                    onStorage = viewModel::openStorage,
                    onExportBackup = {
                        val date = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
                        backupExporter.launch("随身听备份-$date.museumreader")
                    },
                    onImportBackup = { backupImporter.launch(arrayOf(BackupManager.MIME_TYPE, "application/octet-stream", "application/zip", "application/json", "text/plain")) },

                    onOpenTtsSettings = {
                        runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    },
                    snackbar = snackbar,
                )
                AppPage.EDITOR -> EditorScreen(
                    controller = viewModel.editor,
                    onBack = viewModel::closeEditor,
                    snackbar = snackbar,
                )
                AppPage.LIBRARY -> LibraryScreen(
                    ui = ui,
                    onBack = viewModel::closeLibrary,
                    onSavePlaylist = viewModel::savePlaylist,
                    onDeletePlaylist = viewModel::deletePlaylist,
                    onSelectRoute = viewModel::selectRoute,
                    onOpenRouteDocument = viewModel::openRouteDocument,
                    snackbar = snackbar,
                )
                AppPage.CLOUD_VOICE -> CloudVoiceScreen(
                    ui = ui,
                    onBack = viewModel::closeCloudVoice,
                    onPreferences = viewModel::updateCloudPreferences,
                    onSaveXunfei = viewModel::saveXunfeiCredentials,
                    onSaveBaidu = viewModel::saveBaiduCredentials,
                    onClearCredentials = viewModel::clearCloudCredentials,
                    onPreview = viewModel::previewCloudVoice,
                    onGenerate = viewModel::generateCloudAudioForCurrentDocument,
                    onCancelGeneration = viewModel::cancelCloudGeneration,
                    snackbar = snackbar,
                )
                AppPage.STORAGE -> StorageScreen(ui = ui, onBack = viewModel::closeStorage,
                    onRefresh = viewModel::refreshStorage, onRestoreDocument = viewModel::restoreDeletedDocument,
                    onAction = viewModel::processStorageAction, onRestoreAudio = viewModel::restoreStoredAudio,
                    onPreview = viewModel::previewStoredFile,
                    onRecovery = { viewModel.importBackup(android.net.Uri.fromFile(java.io.File(it))) },
                    onExportAudio = { path ->
                        viewModel.stageAudioExport(path)
                        audioExporter.launch(java.io.File(path).name)
                    },
                    onPending = viewModel::processPendingFromStorage, snackbar = snackbar)
                AppPage.RECORDING -> RecordingScreen(
                    ui = ui,
                    onBack = viewModel::closeRecording,
                    onSelect = viewModel::selectRecordingSentence,
                    onStart = { index ->
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            viewModel.startRecording(index)
                        } else {
                            pendingRecordingIndex = index
                            recordingPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStop = { viewModel.stopRecording(true) },
                    onCancel = { viewModel.stopRecording(false) },
                    onPreview = viewModel::previewRecording,
                    onDelete = viewModel::deleteRecording,
                    onClearRecordings = { viewModel.clearCurrentAudio(AudioKind.RECORDING) },
                    onClearCloud = { viewModel.clearCurrentAudio(AudioKind.CLOUD) },
                    onSavePending = { viewModel.savePendingRecording() },
                    onDiscardPending = { viewModel.discardPendingRecording() },
                    onPreviewPending = viewModel::previewPendingRecording,
                    onConfirmExit = viewModel::confirmRecordingExit,
                    onCancelExit = viewModel::cancelRecordingExit,
                    onPermissions = {
                        context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + context.packageName)))
                    },
                    snackbar = snackbar,
                )
            }

            }
            if (ui.isBusy) {
                Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.18f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 4.dp) {
                            Row(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp).semantics(mergeDescendants = true) {
                                    liveRegion = LiveRegionMode.Polite
                                    paneTitle = "正在处理"
                                },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                                Text(ui.busyLabel, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                }
            }
        }

        ui.pendingImport?.let { imported ->
            ImportPreviewDialog(
                imported = imported,
                onDismiss = viewModel::cancelImport,
                onConfirm = viewModel::confirmImport,
            )
        }
        ui.pendingBackup?.let { prepared ->
            BackupRestoreDialog(prepared.preview, viewModel::cancelBackup, viewModel::confirmBackup)
        }
        if (ui.backupWarnings.isNotEmpty() || ui.backupRecoveryPoint != null) {
            BackupResultDialog(ui.backupWarnings, ui.backupRecoveryPoint != null, viewModel::dismissBackupResult)
        }
        ui.failure?.let { failure ->
            OperationFailureDialog(failure, onDismiss = viewModel::dismissFailure, onRecover = {
                viewModel.dismissFailure()
                when (failure.operation) {
                    UserOperation.IMPORT -> filePicker.launch(arrayOf("text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword"))
                    UserOperation.BACKUP_EXPORT -> backupExporter.launch("随身听备份-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date()) + ".museumreader")
                    UserOperation.BACKUP_READ, UserOperation.BACKUP_RESTORE -> backupImporter.launch(arrayOf(BackupManager.MIME_TYPE, "application/octet-stream", "application/zip", "application/json", "text/plain"))
                    UserOperation.CLOUD, UserOperation.CREDENTIALS -> viewModel.openCloudVoice()
                    else -> Unit
                }
            })
        }
    }
}

@Composable
internal fun HomeScreen(
    ui: AppUiState,
    playback: PlaybackSnapshot,
    onPaste: () -> Unit,
    onImport: () -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onEdit: (ReaderDocument) -> Unit,
    onDelete: (String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onSettings: () -> Unit,
    onLibrary: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    snackbar: SnackbarHostState,
) {
    var renameDocument by remember { mutableStateOf<ReaderDocument?>(null) }
    var deleteDocument by remember { mutableStateOf<ReaderDocument?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (playback.documentId != null && playback.sentences.isNotEmpty()) {
                HomeMiniPlayer(
                    playback = playback,
                    onOpen = { playback.documentId?.let(onOpen) },
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                )
            }
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 22.dp,
                end = 22.dp,
                top = insets.calculateTopPadding() + 10.dp,
                bottom = insets.calculateBottomPadding() + 18.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("随身听", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "随时听，慢慢熟悉每一段",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = onLibrary,
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(Icons.Rounded.Route, "讲解路线")
                    }
                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier.size(52.dp),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Icon(Icons.Rounded.Settings, "设置")
                    }
                }
            }
            item { Spacer(Modifier.height(6.dp)) }
            if (ui.ttsStatus == TtsStatus.MISSING || ui.ttsStatus == TtsStatus.ERROR) {
                item { TtsHealthBanner(onSettings) }
            }
            item {
                PrimaryActionCard(
                    icon = Icons.Rounded.ContentPaste,
                    title = "从剪贴板新建",
                    subtitle = "复制讲解词后，点这里直接创建",
                    onClick = onPaste,
                    prominent = true,
                )
            }
            item {
                PrimaryActionCard(
                    icon = Icons.Rounded.FileOpen,
                    title = "导入文件",
                    subtitle = "支持 TXT 与 Word DOCX",
                    onClick = onImport,
                    prominent = false,
                )
            }
            item {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("最近文稿", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text(
                        "${ui.documents.size} 篇",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (ui.documents.isEmpty()) {
                item { EmptyLibrary() }
            } else {
                items(ui.documents, key = { it.id }) { document ->
                    DocumentCard(
                        document = if (document.id == playback.documentId) document.copy(lastSentence = playback.currentIndex) else document,
                        onOpen = { onOpen(document.id) },
                        onRename = { renameDocument = document },
                        onEdit = { onEdit(document) },
                        onPin = { onPin(document.id, !document.isPinned) },
                        onDelete = { deleteDocument = document },
                    )
                }
            }
        }
    }

    renameDocument?.let { document ->
        RenameDialog(
            initialTitle = document.title,
            needsNameReview = document.needsNameReview,
            onDismiss = { renameDocument = null },
            onConfirm = {
                onRename(document.id, it)
                renameDocument = null
            },
        )
    }
    deleteDocument?.let { document ->
        AlertDialog(
            onDismissRequest = { deleteDocument = null },
            title = { Text("删除这篇讲解词？") },
            text = { Text("“${document.title}”将从手机中删除，删除后可立即撤销。") },
            confirmButton = {
                TextButton(onClick = { onDelete(document.id); deleteDocument = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteDocument = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun TtsHealthBanner(onSettings: () -> Unit) {
    Surface(
        onClick = onSettings,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("中文朗读尚未就绪", style = MaterialTheme.typography.titleMedium)
                Text("点这里检查或安装系统中文声音", style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Rounded.NavigateNext, null)
        }
    }
}

@Composable
private fun HomeMiniPlayer(
    playback: PlaybackSnapshot,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 14.dp, tonalElevation = 2.dp) {
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(6.dp),
                ) {
                    Text(playback.documentTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "第 ${playback.currentIndex + 1} / ${playback.sentences.size} 句 · 点按返回正文",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.NavigateBefore, "上一句")
                }
                FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
                    Icon(if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.isPlaying) "暂停" else "继续")
                }
                IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.NavigateNext, "下一句")
                }
            }
        }
    }
}

@Composable
private fun PrimaryActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    prominent: Boolean,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (prominent) 1.dp else 0.dp),
        border = if (prominent) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (prominent) Color.White.copy(alpha = 0.16f) else MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, null, modifier = Modifier.size(27.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (prominent) MaterialTheme.colorScheme.prominentCardSubtitleColor
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.NavigateNext, null)
        }
    }
}

@Composable
private fun EmptyLibrary() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.LibraryBooks, null, modifier = Modifier.size(32.dp))
            }
        }
        Text("还没有讲解词", style = MaterialTheme.typography.titleLarge)
        Text(
            "从剪贴板创建，或导入手机里的文稿",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DocumentCard(
    document: ReaderDocument,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, top = 18.dp, bottom = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    (if (document.isPinned) "置顶 · " else "") + document.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (document.needsNameReview) Text("旧名称过长，可重命名修正", style = MaterialTheme.typography.bodySmall)
                Text(
                    "${sourceLabel(document.source)} · ${formatDate(document.updatedAt)} · 上次第 ${document.lastSentence + 1} 句",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.MoreHoriz, "更多")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (document.isPinned) "取消置顶" else "置顶") },
                        leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                        onClick = { menuOpen = false; onPin() },
                    )
                    DropdownMenuItem(
                        text = { Text("编辑文稿") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportPreviewDialog(
    imported: ImportedDocument,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var title by remember(imported) { mutableStateOf(imported.title) }
    var text by remember(imported) { mutableStateOf(imported.text) }
    val segmented = remember(text) { TextSegmenter.segment(text) }
    InputDialog(title = "导入前确认", onDismiss = onDismiss, actions = {
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        Button(onClick = { onConfirm(title.trim(), text) },
            enabled = title.isNotBlank() && segmented.sentences.isNotEmpty(),
            modifier = Modifier.heightIn(min = 48.dp)) { Text("确认导入") }
    }) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("文稿名称") },
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        "${text.length} 字 · ${segmented.paragraphs.size} 段 · ${segmented.sentences.size} 句",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    label = { Text("正文预览（可直接修正）") },
                )
    }
}

@Composable
private fun RenameDialog(initialTitle: String, needsNameReview: Boolean = false, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    InputDialog(title = "重命名", onDismiss = onDismiss, actions = {
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        TextButton(onClick = { onConfirm(title) }, enabled = title.isNotBlank() && title.length <= 1000,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("保存") }
    }) {
            if (needsNameReview) Text("原名称过长，当前仅显示前部。确认保存将修正文稿名称，正文与录音保留。")
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("文稿名称") },
                modifier = Modifier.fillMaxWidth(),
            )
    }
}

@Composable
private fun EditDocumentDialog(
    document: ReaderDocument,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var title by remember(document.id) { mutableStateOf(document.title) }
    var text by remember(document.id) { mutableStateOf(document.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑讲解词") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("文稿名称") },
                )
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    label = { Text("讲解词正文") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title, text) }, enabled = title.isNotBlank() && text.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun sourceLabel(source: DocumentSource): String = when (source) {
    DocumentSource.CLIPBOARD -> "剪贴板"
    DocumentSource.TXT -> "TXT"
    DocumentSource.DOCX -> "Word"
}

private fun loopLabel(mode: LoopMode): String = when (mode) {
    LoopMode.NONE -> "不循环"
    LoopMode.ALL -> "全文循环"
    LoopMode.RANGE -> "范围循环"
}

private fun formatDate(time: Long): String =
    SimpleDateFormat("M月d日", Locale.SIMPLIFIED_CHINESE).format(Date(time))

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.SIMPLIFIED_CHINESE, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.SIMPLIFIED_CHINESE, "%.1f MB", bytes / (1024.0 * 1024.0))
}
