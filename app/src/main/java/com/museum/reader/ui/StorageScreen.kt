@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.museum.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.museum.reader.data.*
import com.museum.reader.model.AudioKind
import com.museum.reader.recording.PendingRecording
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun StorageScreen(
    ui: AppUiState, onBack: () -> Unit, onRefresh: () -> Unit,
    onRestoreDocument: (String) -> Unit, onAction: (StorageAction) -> Unit,
    onRestoreAudio: (String) -> Unit, onPreview: (String) -> Unit, onRecovery: (String) -> Unit,
    onExportAudio: (String) -> Unit,
    onPending: (PendingRecording, Boolean) -> Unit, snackbar: SnackbarHostState,
) {
    val storage = ui.storage
    var action by remember { mutableStateOf<StorageAction?>(null) }
    var discardPending by remember { mutableStateOf<PendingRecording?>(null) }
    val cloud = storage.items.filter { it.audio.kind == AudioKind.CLOUD && !it.protected }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text("存储恢复") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回设置") }
        }, actions = { TextButton(onClick = onRefresh) { Text("刷新") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("本人录音 ${bytesLabel(storage.recordingBytes)} · 云缓存 ${bytesLabel(storage.cloudBytes)}")
                Text("旧音频 ${bytesLabel(storage.oldBytes)} · 待确认文件 ${bytesLabel(storage.unlinkedBytes)}")
                Text("按实际文件去重统计。恢复区、历史版本、待确认录音和正在播放的文件受保护。", style = MaterialTheme.typography.bodySmall)
                storage.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick = { action = StorageAction.Cloud(cloud.map { it.audio }, cloud.distinctBy { it.audio.path }.sumOf { it.bytes }) },
                    enabled = cloud.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("清理可移除的云缓存 · ${cloud.size} 条")
                }
            }
            if (ui.pendingRecordings.isNotEmpty()) item { Text("待确认录音", style = MaterialTheme.typography.titleLarge) }
            items(ui.pendingRecordings, key = { "pending-" + it.id }) { pending ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(ui.documents.firstOrNull { it.id == pending.documentId }?.title ?: "已删除或不可用文稿")
                        Text(if (pending.playable) "保留的原录音 · ${pending.durationMs / 1000f} 秒" else "原文件保留，但暂不可播放")
                        TextButton(onClick = { onPreview(pending.path) }, enabled = pending.playable) { Text("试听本次录音") }
                        TextButton(onClick = { onPending(pending, true) }, enabled = pending.playable) { Text("确认保存为本人录音") }
                        TextButton(onClick = { discardPending = pending }) { Text("放弃本次录音") }
                    }
                }
            }
            item { Text("最近删除", style = MaterialTheme.typography.titleLarge) }
            if (storage.trash.isEmpty()) item { Text("没有最近删除的文稿。文稿删除后至少保留 7 天，含本人录音时也不会自动永久删除。") }
            items(storage.trash, key = { "trash-" + it.id }) { doc ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(doc.title, style = MaterialTheme.typography.titleMedium)
                        Text("本人录音 ${doc.recordings} 条 · 云缓存 ${doc.cloud} 条 · ${bytesLabel(doc.bytes)}")
                        val eligible = doc.canPurge(System.currentTimeMillis())
                        Text(if (eligible) "已到保留期，可手动清理" else "至少保留至 " + dateLabel(doc.trashedAt + StorageMaintenance.RETENTION_MS))
                        TextButton(onClick = { onRestoreDocument(doc.id) }) { Text("恢复文稿") }
                        TextButton(onClick = { action = StorageAction.Purge(doc) }, enabled = eligible) { Text("永久删除文稿及音频") }
                    }
                }
            }
            item { Text("旧音频与缺失文件", style = MaterialTheme.typography.titleLarge) }
            items(storage.items.filter { it.old }, key = { it.audio.id }) { item ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.documentTitle + " · " + if (item.audio.kind == AudioKind.RECORDING) "本人录音" else "云缓存")
                        Text(item.sentenceText, maxLines = 2)
                        Text(if (item.missing) "文件缺失，保留了关联记录" else bytesLabel(item.bytes))
                        if (item.protected) Text("用于历史、恢复区、待确认录音或正在播放，暂不永久清理。")
                        TextButton(onClick = { onPreview(item.audio.path) }, enabled = !item.missing) { Text("试听旧音频") }
                        TextButton(onClick = { onRestoreAudio(item.audio.id) }, enabled = !item.missing) { Text("恢复为本句有效音频") }
                        TextButton(onClick = { action = StorageAction.Old(item) }, enabled = !item.protected) { Text("永久清理这条旧音频") }
                    }
                }
            }
            item { Text("待确认的无引用文件", style = MaterialTheme.typography.titleLarge) }
            if (storage.unlinked.isEmpty()) item { Text("没有发现无引用文件。可信旧录音会在打开所属文稿时自动核对，不能确认的文件始终保留。") }
            items(storage.unlinked, key = { it.path }) { file ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(java.io.File(file.path).name, maxLines = 2)
                        Text("${bytesLabel(file.bytes)} · ${dateLabel(file.modifiedAt)}")
                        Text("无法确定所属句子，可能包含本人录音。")
                        TextButton(onClick = { onExportAudio(file.path) }) { Text("另存原音频文件") }
                        TextButton(onClick = { onPreview(file.path) }) { Text("尝试试听") }
                        TextButton(onClick = { action = StorageAction.Unlinked(file) }) { Text("永久清理此文件") }
                    }
                }
            }
            item { Text("覆盖恢复前的恢复点", style = MaterialTheme.typography.titleLarge) }
            if (storage.recoveryPoints.isEmpty()) item { Text("尚无恢复点。覆盖导入前会保留当时的完整备份。") }
            items(storage.recoveryPoints, key = { it.path }) { point ->
                OutlinedButton(onClick = { onRecovery(point.path) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${dateLabel(point.createdAt)} · ${bytesLabel(point.bytes)} · 校验并恢复")
                }
            }
        }
    }
    action?.let { pending ->
        var acknowledged by remember(pending) { mutableStateOf(false) }
        val description = when (pending) {
            is StorageAction.Cloud -> "永久清理 ${pending.audio.size} 条云缓存（${bytesLabel(pending.bytes)}），不会删除本人录音。之后可重新生成。"
            is StorageAction.Old -> "永久清理“${pending.item.documentTitle}”的一条${if (pending.item.audio.kind == AudioKind.RECORDING) "本人录音" else "云缓存"}（${bytesLabel(pending.item.bytes)}）。"
            is StorageAction.Unlinked -> "永久清理一份无法确认所属句子的文件（${bytesLabel(pending.item.bytes)}），可能是本人录音。"
            is StorageAction.Purge -> "永久删除“${pending.item.title}”，包含 ${pending.item.recordings} 条本人录音和 ${pending.item.cloud} 条云缓存（${bytesLabel(pending.item.bytes)}）。"
        }
        AlertDialog(onDismissRequest = { action = null }, title = { Text("确认永久清理？") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(description)
                Text("此操作不可撤销。请先确认已有需要的备份，取消不会修改任何内容。")
                Row(Modifier.fillMaxWidth().toggleable(acknowledged, role = Role.Checkbox,
                    onValueChange = { acknowledged = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(acknowledged, onCheckedChange = null)
                    Text("我已核对影响范围，确认永久清理")
                }
            } }, confirmButton = {
                TextButton(onClick = { onAction(pending); action = null }, enabled = acknowledged) { Text("永久清理") }
            }, dismissButton = { TextButton(onClick = { action = null }) { Text("取消") } })
    }
    discardPending?.let { pending ->
        AlertDialog(onDismissRequest = { discardPending = null }, title = { Text("放弃本次待确认录音？") },
            text = { Text("仅删除这次尚未保存的文件，原有效录音保留。") },
            confirmButton = { TextButton(onClick = { onPending(pending, false); discardPending = null }) { Text("放弃本次录音") } },
            dismissButton = { TextButton(onClick = { discardPending = null }) { Text("取消") } })
    }
}
private fun bytesLabel(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.CHINA, "%.1f MB", bytes / 1048576f)
    else -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024f)
}
private fun dateLabel(value: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(value))
