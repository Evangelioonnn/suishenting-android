package com.museum.reader.ui

import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** The actions scroll with the field when a large keyboard leaves only a short window. */
@Composable
internal fun TextSearchDialog(
    query: String,
    onQuery: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    explanation: String? = null,
) {
    InputDialog(title = "查找正文", onDismiss = onDismiss, actions = {
        val confirm = searchConfirmation(onConfirm)
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        TextButton(onClick = confirm, enabled = query.isNotEmpty(),
            modifier = Modifier.heightIn(min = 48.dp)) { Text("查看结果") }
    }) {
        val confirm = searchConfirmation(onConfirm)
        OutlinedTextField(
            value = query, onValueChange = onQuery,
            label = { Text("查找文字，忽略英文大小写") },
            modifier = Modifier.fillMaxWidth(), maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotEmpty()) confirm() }),
        )
        if (explanation != null) Text(explanation, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun searchConfirmation(onConfirm: () -> Unit): () -> Unit {
    val focus = LocalFocusManager.current
    val view = LocalView.current
    return {
        // Use the search dialog's own window token while it is still attached.
        // Hiding through the underlying activity can leave this IME session open.
        focus.clearFocus(force = true)
        view.context.getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(view.windowToken, 0)
        onConfirm()
    }
}
