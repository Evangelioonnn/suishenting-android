package com.museum.reader.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun OperationFailureDialog(failure: OperationFailure, onDismiss: () -> Unit, onRecover: (() -> Unit)? = null) {
    val recoveryLabel = when (failure.operation) {
        UserOperation.IMPORT -> "重新选择文件"
        UserOperation.BACKUP_EXPORT -> "重新选择保存位置"
        UserOperation.BACKUP_READ, UserOperation.BACKUP_RESTORE -> "重新选择备份"
        UserOperation.CLOUD, UserOperation.CREDENTIALS -> "检查云声音设置"
        else -> null
    }
    InputDialog(title = failure.operation.title, onDismiss = onDismiss, actions = {
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
        if (onRecover != null && recoveryLabel != null) TextButton(onClick = onRecover,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(recoveryLabel) }
    }) {
        Text(failure.message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            style = MaterialTheme.typography.bodyLarge)
    }
}
