@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.museum.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.museum.reader.model.AudioKind

@Composable
internal fun RecordingScreen(
    ui: AppUiState, onBack: () -> Unit, onSelect: (Int) -> Unit, onStart: (Int) -> Unit,
    onStop: () -> Unit, onCancel: () -> Unit, onPreview: (Int) -> Unit, onDelete: (Int) -> Unit,
    onClearRecordings: () -> Unit, onClearCloud: () -> Unit,
    onSavePending: () -> Unit, onDiscardPending: () -> Unit, onPreviewPending: () -> Unit,
    onConfirmExit: (Boolean) -> Unit, onCancelExit: () -> Unit,
    onPermissions: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val r = ui.recording
    val sentence = ui.content.sentences.getOrNull(r.currentIndex)
    val locked = r.isRecording || r.processing || r.pending != null
    var deleting by remember { mutableStateOf<Int?>(null) }
    var clear by remember { mutableStateOf<AudioKind?>(null) }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text("录制本人声音") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (r.permissionDenied) item {
                Text("录音需要麦克风权限。允许后可重新录制，原有录音不受影响。")
                OutlinedButton(onClick = onPermissions, modifier = Modifier.fillMaxWidth()) { Text("打开应用权限设置") }
            }
            item { Text("保留完整原录音，不固定裁去首尾。重录先试听并确认，原有效录音在确认前保持可用。") }
            item { Text("第 ${r.currentIndex + 1}/${ui.content.sentences.size} 句 · 已录 ${r.recordedSentenceIds.size} 句",
                style = MaterialTheme.typography.titleMedium) }
            item { Text(sentence?.text.orEmpty(), style = MaterialTheme.typography.headlineSmall) }
            if (r.processing) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在校验并整理录音，请稍候…")
            }
            if (r.isRecording) item {
                LinearProgressIndicator(progress = { r.amplitude.coerceIn(0.02f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(if (r.amplitude < 0.08f) "正在录音 · 声音较小，可以靠近麦克风" else "正在录音")
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("停止并试听确认") }
                TextButton(onClick = onCancel) { Text("放弃本次录音，保留原录音") }
            }
            r.pending?.let { pending -> item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("待确认录音", style = MaterialTheme.typography.titleLarge)
                        Text(if (pending.playable) "已保留完整原文件 · ${pending.durationMs / 1000f} 秒"
                            else "这份录音暂不可播放，原有效录音保持不变。可保留待处理，或放弃本次文件。")
                        OutlinedButton(onClick = onPreviewPending, enabled = pending.playable && !r.processing,
                            modifier = Modifier.fillMaxWidth()) { Text("试听本次录音") }
                        Button(onClick = onSavePending, enabled = pending.playable && !r.processing,
                            modifier = Modifier.fillMaxWidth()) { Text("确认保存为本人录音") }
                        TextButton(onClick = onDiscardPending, enabled = !r.processing) { Text("放弃本次录音") }
                    }
                }
            } }
            if (!r.isRecording && r.pending == null) item {
                Button(onClick = { onStart(r.currentIndex) }, enabled = !r.processing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (sentence?.id in r.recordedSentenceIds) "重新录制这一句" else "开始录制这一句")
                }
                if (sentence?.id in r.recordedSentenceIds) {
                    OutlinedButton(onClick = { onPreview(r.currentIndex) }, enabled = !r.processing,
                        modifier = Modifier.fillMaxWidth()) { Text("试听已保存录音") }
                    TextButton(onClick = { deleting = r.currentIndex }, enabled = !r.processing) { Text("移除当前句录音") }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { onSelect(r.currentIndex - 1) }, enabled = !locked && r.currentIndex > 0) { Text("上一句") }
                    TextButton(onClick = { onSelect(r.currentIndex + 1) }, enabled = !locked && r.currentIndex < ui.content.sentences.lastIndex) { Text("下一句") }
                }
            }
            item { Text("逐句定位", style = MaterialTheme.typography.titleLarge) }
            itemsIndexed(ui.content.sentences, key = { _, sentence -> sentence.id }) { index, item ->
                OutlinedButton(onClick = { onSelect(index) }, enabled = !locked, modifier = Modifier.fillMaxWidth()) {
                    Text("${index + 1}. ${item.text}${if (item.id in r.recordedSentenceIds) " · 已录制" else ""}", maxLines = 3)
                }
            }
            item {
                Text("批量音频维护", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { clear = AudioKind.RECORDING }, enabled = !locked && r.recordingBytes > 0) {
                    Text("停用本文本人录音（保留旧音频）")
                }
                TextButton(onClick = { clear = AudioKind.CLOUD }, enabled = !locked && r.cloudBytes > 0) {
                    Text("停用本文云缓存（保留旧音频）")
                }
                Text("永久清理和旧录音恢复请进入设置中的存储维护。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    deleting?.let { index -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("移除此句录音？") },
        text = { Text("从正常播放中移除，文件保留到旧音频，可在存储维护中恢复。") },
        confirmButton = { TextButton(onClick = { onDelete(index); deleting = null }) { Text("移除录音") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    clear?.let { kind -> AlertDialog(onDismissRequest = { clear = null }, title = { Text("停用本文音频？") },
        text = { Text("影响本文${if (kind == AudioKind.RECORDING) r.recordedSentenceIds.size.toString() + " 句本人录音" else "的云缓存"}。旧文件保留；取消不作修改。") },
        confirmButton = { TextButton(onClick = {
            if (kind == AudioKind.RECORDING) onClearRecordings() else onClearCloud(); clear = null
        }) { Text("确认停用") } },
        dismissButton = { TextButton(onClick = { clear = null }) { Text("取消") } }) }
    if (r.exitConfirm) AlertDialog(onDismissRequest = onCancelExit, title = { Text("离开录音前保留本次内容？") },
        text = { Text("保存会先校验本次录音，成功后才替换有效录音；放弃只移除本次文件。") },
        confirmButton = { TextButton(onClick = { onConfirmExit(true) }) { Text("保存并返回") } },
        dismissButton = { Column {
            TextButton(onClick = { onConfirmExit(false) }) { Text("放弃并返回") }
            TextButton(onClick = onCancelExit) { Text("继续录音") }
        } })
}
