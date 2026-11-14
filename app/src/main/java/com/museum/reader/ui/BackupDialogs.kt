package com.museum.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import com.museum.reader.data.BackupConflictPolicy
import com.museum.reader.data.BackupPreview

@Composable
internal fun BackupRestoreDialog(
    preview: BackupPreview,
    onDismiss: () -> Unit,
    onConfirm: (BackupConflictPolicy, Boolean) -> Unit,
) {
    var policy by remember { mutableStateOf(BackupConflictPolicy.KEEP_LOCAL) }
    var restoreSettings by remember { mutableStateOf(preview.sameDocuments == 0 && preview.conflictTitles.isEmpty()) }
    var acknowledgeWarnings by remember { mutableStateOf(false) }
    var acknowledgeReplacement by remember { mutableStateOf(false) }
    val canRestore = (preview.warnings.isEmpty() || acknowledgeWarnings) &&
        (policy != BackupConflictPolicy.REPLACE || preview.conflictTitles.isEmpty() || acknowledgeReplacement)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复前确认") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("新增 " + preview.newDocuments + " 篇，相同 " + preview.sameDocuments + " 篇，冲突 " +
                    preview.conflictTitles.size + " 篇；可恢复音频 " + preview.audioCount + " 条。")
                if (preview.conflictTitles.isNotEmpty()) {
                    if (preview.audioConflictTitles.isNotEmpty()) {
                        Text("其中有 ${preview.audioConflictTitles.size} 篇录音或云声音与本机不同。默认保留本机内容及音频；需要旧音频时可另存副本或明确覆盖。")
                    }
                    Text("冲突文稿", style = MaterialTheme.typography.titleMedium)
                    preview.conflictTitles.take(20).forEach { Text("• " + it) }
                    if (preview.conflictTitles.size > 20) Text("其余 " + (preview.conflictTitles.size - 20) + " 篇按相同方式处理")
                    BackupConflictPolicy.entries.forEach { option ->
                        Row(Modifier.fillMaxWidth().selectable(policy == option, role = Role.RadioButton,
                            onClick = { policy = option }).heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = policy == option, onClick = null)
                            Text(option.label, Modifier.weight(1f))
                        }
                    }
                    if (policy == BackupConflictPolicy.REPLACE) {
                        Text("覆盖前会保留完整恢复点。恢复过程中将停止朗读。")
                        BackupCheckbox("确认以备份内容覆盖冲突文稿", acknowledgeReplacement) { acknowledgeReplacement = it }
                    }
                }
                BackupCheckbox("恢复备份中的朗读设置", restoreSettings) { restoreSettings = it }
                if (preview.warnings.isNotEmpty()) {
                    Text("有内容不能完整恢复", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium)
                    preview.warnings.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    BackupCheckbox("已了解，将只恢复校验通过的内容", acknowledgeWarnings) { acknowledgeWarnings = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(policy, restoreSettings) }, enabled = canRestore) { Text("确认恢复") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BackupCheckbox(text: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChecked),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Text(text, Modifier.weight(1f))
    }
}

@Composable
internal fun BackupResultDialog(warnings: List<String>, hasRecoveryPoint: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (warnings.isEmpty()) "已保留恢复点" else "恢复结果详情") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasRecoveryPoint) Text("覆盖前的资料已保存为本机恢复点，可在设置的存储维护中查看和恢复。")
                if (warnings.isNotEmpty()) Text("恢复时有以下内容需要注意，请逐项核对：")
                warnings.forEach { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}
