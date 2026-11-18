package com.museum.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val PAGE_SIZE = 2400

/** Prefer a nearby natural break; offsets always address the unmodified original string. */
internal fun importPageEnd(text: String, start: Int): Int {
    val limit = (start + PAGE_SIZE).coerceAtMost(text.length)
    if (limit == text.length) return limit
    for (i in (limit - 1) downTo (limit - PAGE_SIZE / 4)) {
        if (text[i] in "\n。！？.!?") return i + 1
    }
    val splitPair = text[limit - 1].isHighSurrogate() && text[limit].isLowSurrogate()
    val splitNewline = text[limit - 1] == '\r' && text[limit] == '\n'
    return if (splitPair || splitNewline) limit - 1 else limit
}

@Composable
internal fun ImportPreviewBody(text: String, enabled: Boolean, onChange: (String) -> Unit) {
    var previousStarts by rememberSaveable { mutableStateOf(intArrayOf()) }
    var pageStart by rememberSaveable { mutableIntStateOf(0) }
    var pageEnd by rememberSaveable { mutableIntStateOf(importPageEnd(text, 0)) }
    val paged = text.length > PAGE_SIZE || pageStart > 0
    val start = pageStart.coerceIn(0, text.length)
    val end = if (paged) pageEnd.coerceIn(start, text.length) else text.length
    val visible = remember(text, start, end) { text.substring(start, end) }

    fun previousPage() {
        pageEnd = start
        pageStart = previousStarts.last()
        previousStarts = previousStarts.copyOf(previousStarts.lastIndex)
    }
    if (paged) {
        Text("第 ${previousStarts.size + 1} 页 · 正文第 ${start + 1} 至 $end 字", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = ::previousPage, enabled = enabled && previousStarts.isNotEmpty()) { Text("上一页") }
            TextButton(onClick = {
                previousStarts += start
                pageStart = end
                pageEnd = importPageEnd(text, end)
            }, enabled = enabled && end < text.length) { Text("下一页") }
        }
    }
    key(start) {
        OutlinedTextField(value = visible, onValueChange = { input ->
            val edited = if (paged) text.replaceRange(start, end, input) else input
            when {
                input.isEmpty() && start == edited.length && previousStarts.isNotEmpty() -> previousPage()
                input.isEmpty() || input.length > PAGE_SIZE -> pageEnd = importPageEnd(edited, start)
                else -> pageEnd = start + input.length
            }
            onChange(edited)
        }, enabled = enabled, modifier = Modifier.fillMaxWidth().height(260.dp),
            label = { Text("正文预览（可直接修正）") })
    }
}
