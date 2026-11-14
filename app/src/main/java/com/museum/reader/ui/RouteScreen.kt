@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.museum.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun LibraryScreen(
    ui: AppUiState, onBack: () -> Unit,
    onSavePlaylist: (String?, String, List<String>) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onSelectRoute: (String?) -> Unit,
    onOpenRouteDocument: (String, String) -> Unit,
    snackbar: SnackbarHostState,
) {
    val shortWindow = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } < 440.dp
    val titleHeight = if (shortWindow) 64.dp else (64f * LocalDensity.current.fontScale).coerceAtLeast(64f).dp
    val route = ui.playlists.firstOrNull { it.id == ui.routeContextId }
    val documents = remember(ui.documents) { ui.documents.associateBy { it.id } }
    var editing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }
    var deleting by remember { mutableStateOf<String?>(null) }
    fun edit() { title = route?.title.orEmpty(); selected = route?.documentIds.orEmpty(); editing = true }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(expandedHeight = titleHeight, title = { Text(route?.title ?: "讲解路线",
            maxLines = if (shortWindow) 1 else 2, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
            IconButton(onClick = { if (route != null) onSelectRoute(null) else onBack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
            }
        }, actions = {
            TextButton(onClick = ::edit) { Text(if (route == null) "新建" else "编辑") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (route == null) {
                if (ui.playlists.isEmpty()) item { Text("还没有路线。新建后可按参观顺序组织现有文稿。") }
                items(ui.playlists, key = { it.id }) { item ->
                    ElevatedCard(onClick = { onSelectRoute(item.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleLarge)
                            Text("${item.documentIds.count { it in documents }}/${item.documentIds.size} 站可用 · 点击查看")
                        }
                    }
                }
            } else {
                item {
                    Text("按保存顺序逐站查看；到篇末由你决定是否进入下一篇。")
                    route.lastDocumentId?.takeIf { it in documents }?.let { last ->
                        Button(onClick = { onOpenRouteDocument(route.id, last) }) { Text("继续上次：${documents[last]!!.title}") }
                    }
                }
                items(route.documentIds, key = { it }) { id ->
                    val document = documents[id]
                    OutlinedCard(onClick = { if (document != null) onOpenRouteDocument(route.id, id) },
                        modifier = Modifier.fillMaxWidth(), enabled = document != null) {
                        Column(Modifier.padding(16.dp)) {
                            Text("第 ${route.documentIds.indexOf(id) + 1} 站 · ${document?.title ?: "文稿已删除或不可用"}")
                            if (document != null) Text("从第 ${document.lastSentence + 1} 句继续 · 点此查看")
                            else Text("可恢复文稿，或编辑路线后移除此站")
                        }
                    }
                }
                item { TextButton(onClick = { deleting = route.id }) { Text("删除路线") } }
            }
        }
    }
    if (editing) InputDialog(title = if (route == null) "新建路线" else "编辑路线", onDismiss = { editing = false },
        actions = {
            TextButton(onClick = { editing = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
            TextButton(onClick = { onSavePlaylist(route?.id, title, selected); editing = false },
                enabled = title.isNotBlank() && selected.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp)) { Text("保存路线") }
        }) {
                if (route?.needsNameReview == true) Text("原路线名称过长，当前为缩略显示。只调整顺序会保留原名称；重新输入名称后保存可修正。")
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("路线名称") },
                    modifier = Modifier.fillMaxWidth())
                Text("已选顺序")
                selected.forEachIndexed { index, id ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${documents[id]?.title ?: "不可用文稿"}", Modifier.weight(1f))
                        IconButton(onClick = {
                            selected = selected.toMutableList().apply { val item = removeAt(index); add(index - 1, item) }
                        }, enabled = index > 0) { Icon(Icons.Rounded.ArrowUpward, "上移第 ${index + 1} 站") }
                        IconButton(onClick = {
                            selected = selected.toMutableList().apply { val item = removeAt(index); add(index + 1, item) }
                        }, enabled = index < selected.lastIndex) { Icon(Icons.Rounded.ArrowDownward, "下移第 ${index + 1} 站") }
                        IconButton(onClick = { selected = selected - id }) { Icon(Icons.Rounded.Close, "移除此站") }
                    }
                }
                Text("添加文稿")
                ui.documents.forEach { document ->
                    Row(Modifier.fillMaxWidth().toggleable(value = document.id in selected, role = Role.Checkbox,
                        onValueChange = { checked -> selected = if (checked) selected + document.id else selected - document.id }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = document.id in selected, onCheckedChange = null)
                        Text(document.title)
                    }
                }
    }
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条路线？") },
            text = { Text("只删除路线的组织关系，文稿和录音都保留。") },
            confirmButton = { TextButton(onClick = { onDeletePlaylist(id); onSelectRoute(null); deleting = null }) { Text("删除路线") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
