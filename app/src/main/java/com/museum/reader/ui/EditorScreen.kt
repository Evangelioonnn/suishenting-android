package com.museum.reader.ui

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.museum.reader.data.DocumentVersion
import com.museum.reader.data.MAX_NAME_CHARACTERS
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museum.reader.domain.TextPositionIndex
import com.museum.reader.domain.TextSearch
import com.museum.reader.domain.TextSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class CursorEditText(context: Context) : EditText(context) {
    // Importance is only a hint: TextView still notifies an active/manual Autofill
    // session about setText. Manuscripts can exceed Binder's transaction budget.
    // Exclude the body at the protocol boundary while retaining normal IME,
    // selection, clipboard, accessibility and our durable drafts.
    override fun getAutofillType(): Int = android.view.View.AUTOFILL_TYPE_NONE
    override fun getAutofillValue(): android.view.autofill.AutofillValue? = null
    override fun autofill(value: android.view.autofill.AutofillValue) = Unit
    override fun onProvideAutofillStructure(structure: android.view.ViewStructure, flags: Int) {
        structure.setAutofillId(autofillId)
        structure.setAutofillType(android.view.View.AUTOFILL_TYPE_NONE)
        structure.setDataIsSensitive(true)
    }

    var onPosition: ((Int, Int, Int) -> Unit)? = null
    var appliedInstall = -1L
    var installedSearch = -1
    var installingContent = false
    fun revealSearchResult(start: Int, end: Int) {
        // Search browsing needs a visible selection, but must not reopen the keyboard
        // when focus returns from the search dialog or the user moves between hits.
        showSoftInputOnFocus = false
        requestFocus()
        setSelection(start, end)
        context.getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(windowToken, 0)
        post { bringPointIntoView(start) }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) showSoftInputOnFocus = true
        return super.dispatchTouchEvent(event)
    }
    override fun performClick(): Boolean {
        // Accessibility clicks must restore the same editing behavior as a touch.
        showSoftInputOnFocus = true
        return super.performClick()
    }
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onPosition?.invoke(selStart, selEnd, scrollY)
    }
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onPosition?.invoke(selectionStart, selectionEnd, t)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorScreen(controller: EditorController, onBack: () -> Unit, snackbar: SnackbarHostState) {
    val editor by controller.state.collectAsStateWithLifecycle()
    var query by rememberSaveable(editor.document?.id) { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchDialog by rememberSaveable { mutableStateOf(false) }
    var titleDialog by rememberSaveable { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var versionNameTarget by remember { mutableStateOf<DocumentVersion?>(null) }
    var versionName by remember { mutableStateOf("") }
    var discardOpen by remember { mutableStateOf(false) }
    var errorDetailsOpen by remember { mutableStateOf(false) }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var native: CursorEditText? by remember { mutableStateOf(null) }
    var bodyFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeShown = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val shortWindow = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } < 440.dp
    val compact = fullScreen || shortWindow || bodyFocused && imeShown
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(editor.error, editor.pendingDraft, versionNameTarget) {
        errorDetailsOpen = editor.error != null && editor.pendingDraft == null && versionNameTarget == null
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) controller.flush()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); controller.flush(); native = null }
    }
    val requestBack = { if (editor.dirty) discardOpen = true else onBack() }
    BackHandler(onBack = requestBack)

    val index by produceState<TextPositionIndex?>(null, editor.text) {
        value = null
        delay(180)
        value = withContext(Dispatchers.Default) {
            val job = currentCoroutineContext()
            TextPositionIndex.raw(editor.text) { job.ensureActive() }
        }
    }
    val results by produceState<TextSearchResult?>(null, editor.text, query, searchOpen) {
        value = null
        if (!searchOpen || query.isEmpty()) return@produceState
        delay(150)
        value = withContext(Dispatchers.Default) {
            val job = currentCoroutineContext()
            TextSearch.find(editor.text, query) { job.ensureActive() }
        }
    }
    var hit by remember(editor.text, query) { mutableIntStateOf(0) }
    fun locate(target: Int) {
        val found = results ?: return
        if (found.count == 0) return
        hit = Math.floorMod(target, found.count)
        found.range(hit)?.let { range ->
            native?.revealSearchResult(range.first, range.last + 1)
        }
    }
    LaunchedEffect(results) {
        if ((results?.count ?: 0) > 0) locate(0)
        else if (results != null) native?.let { it.setSelection(it.selectionEnd.coerceAtLeast(0)) }
    }
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val highlight = MaterialTheme.colorScheme.tertiaryContainer.toArgb()
    val editorActions: @Composable RowScope.() -> Unit = {
            Box {
                TextButton(onClick = { moreOpen = true }) { Text("更多") }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(text = { Text(if (searchOpen) "关闭搜索" else "搜索正文") }, onClick = {
                        moreOpen = false; searchOpen = !searchOpen
                        if (!searchOpen) { query = ""; native?.setSelection(editor.selectionEnd) } else searchDialog = true
                    })
                    if (compact) DropdownMenuItem(text = { Text("文稿名称与统计") }, onClick = {
                        moreOpen = false; keyboard?.hide(); titleDialog = true
                    })
                    DropdownMenuItem(text = { Text("版本历史") }, enabled = editor.versions.isNotEmpty(),
                        onClick = { moreOpen = false; historyOpen = true })
                    DropdownMenuItem(text = { Text(if (fullScreen) "退出全屏编辑" else "全屏编辑") },
                        onClick = { moreOpen = false; fullScreen = !fullScreen })
                }
            }
            TextButton(onClick = { controller.save() }, enabled = !editor.saving) { Text("保存") }
    }
    val backButton: @Composable () -> Unit = {
        IconButton(onClick = requestBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        if (shortWindow && imeShown) Surface {
            Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                backButton()
                Text("编辑", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                editorActions()
            }
        } else TopAppBar(title = { Text("编辑") }, navigationIcon = backButton, actions = editorActions)
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!compact) OutlinedTextField(value = editor.title,
                onValueChange = { controller.change(it, editor.text) }, label = { Text("文稿名称") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            if (editor.error != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("操作未完成", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { errorDetailsOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看原因") }
            } else Text(editor.draftStatus, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (searchOpen) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { searchDialog = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Search, "修改查找文字")
                }
                Text(if (query.isEmpty()) "请输入文字" else if (results == null) "查找中…"
                    else if (results!!.count == 0) "0/0" else "${hit + 1}/${results!!.count}", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { locate(hit - 1) }, enabled = (results?.count ?: 0) > 0,
                    modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.NavigateBefore, "上一处") }
                IconButton(onClick = { locate(hit + 1) }, enabled = (results?.count ?: 0) > 0,
                    modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.NavigateNext, "下一处") }
                IconButton(onClick = { searchOpen = false; query = ""; native?.setSelection(editor.selectionEnd) },
                    modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "关闭搜索") }
            }
            if (!compact) Text(if (index == null) "${editor.text.length} 字 · 正在更新位置…" else
                "${editor.text.length} 字 · 第 ${index!!.paragraphAt(editor.selectionStart) + 1} 段 · 第 ${index!!.sentenceAt(editor.selectionStart) + 1} 句",
                style = MaterialTheme.typography.bodySmall)
            AndroidView(modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    CursorEditText(context).apply {
                        // The editor owns durable drafts. Sending a million-character value to the
                        // framework autofill/saved-state services exceeds Binder limits on API 26.
                        importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        isSaveEnabled = false
                        gravity = Gravity.TOP or Gravity.START
                        textSize = 20f
                        setLineSpacing(0f, 1.45f)
                        setPadding(12, 12, 12, 36)
                        setHorizontallyScrolling(false)
                        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        isVerticalScrollBarEnabled = true
                        filters = arrayOf(InputFilter.LengthFilter(1_000_000))
                        contentDescription = "讲解词正文"
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                            override fun afterTextChanged(text: Editable?) {
                                if (installingContent) return
                                val value = text?.toString() ?: return
                                val current = controller.state.value
                                if (value != current.text) {
                                    controller.change(current.title, value)
                                    controller.position(selectionStart, selectionEnd, scrollY)
                                }
                            }
                        })
                        setOnFocusChangeListener { _, focused -> bodyFocused = focused }
                        native = this
                    }
                }, update = { view ->
                    view.setTextColor(foreground)
                    view.highlightColor = highlight
                    if (view.appliedInstall != editor.installRevision) {
                        view.onPosition = null
                        // Installing a draft/version is not user input. A TextWatcher attached to
                        // a reused AndroidView must not write an older Compose snapshot back over it.
                        view.installingContent = true
                        try {
                            view.setText(editor.text)
                            view.setSelection(editor.selectionStart.coerceIn(0, editor.text.length),
                                editor.selectionEnd.coerceIn(0, editor.text.length))
                            val scroll = editor.scrollY
                            view.post { view.scrollTo(0, scroll) }
                            view.appliedInstall = editor.installRevision
                        } finally { view.installingContent = false }
                    }
                    view.onPosition = controller::position
                })
        }
    }
    if (titleDialog) InputDialog(title = "文稿名称与统计", onDismiss = { titleDialog = false }, actions = {
        TextButton(onClick = { titleDialog = false; keyboard?.hide() },
            modifier = Modifier.heightIn(min = 48.dp)) { Text("完成") }
    }) {
            if (editor.retainedTitle != null) Text("原名称过长，当前仅显示前部；未改名称时保留完整原值。输入新名称后，仍需保存正式文稿。")
            OutlinedTextField(value = editor.title, onValueChange = { controller.change(it, editor.text) },
                label = { Text("文稿名称") }, modifier = Modifier.fillMaxWidth())
            Text("${editor.text.length} 字 · ${editor.draftStatus}")
    }
    if (searchDialog) TextSearchDialog(query = query, onQuery = { query = it },
        onConfirm = { searchDialog = false; keyboard?.hide() },
        onDismiss = { searchDialog = false; if (query.isEmpty()) searchOpen = false })
    editor.pendingDraft?.let {
        InputDialog(title = "发现未提交的草稿", onDismiss = {}, scrollResetKey = editor.error, actions = {
            TextButton(onClick = { controller.chooseDraft(true) }, enabled = !editor.choosingDraft,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("继续草稿") }
            TextButton(onClick = { controller.chooseDraft(false) }, enabled = !editor.choosingDraft,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("使用正式版本") }
        }) {
            editor.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("草稿与正式文稿分开保存。请选择继续草稿，或使用正式版本并放弃这份草稿。")
            if (editor.choosingDraft) Text("正在放弃未提交草稿…")
        }
    }
    if (errorDetailsOpen && editor.error != null && editor.pendingDraft == null && versionNameTarget == null) InputDialog(
        title = "编辑操作未完成", onDismiss = { errorDetailsOpen = false }, actions = {
            TextButton(onClick = { errorDetailsOpen = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回编辑") }
        }) { Text(checkNotNull(editor.error), color = MaterialTheme.colorScheme.error) }
    if (discardOpen) AlertDialog(onDismissRequest = { discardOpen = false }, title = { Text("保留当前修改？") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("草稿尚未提交为正式文稿。")
            TextButton(onClick = { discardOpen = false }) { Text("继续编辑") }
            TextButton(onClick = { controller.discard(onBack) }, enabled = !editor.saving) { Text("放弃修改并退出") }
        } },
        confirmButton = { TextButton(onClick = { controller.save { discardOpen = false; onBack() } },
            enabled = !editor.saving) { Text("保存并退出") } })
    if (historyOpen) AlertDialog(onDismissRequest = { historyOpen = false }, title = { Text("版本历史") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("正文变化才保留旧版，最多 5 版。恢复前先保存当前修改。")
            editor.versions.forEach { version ->
                if (version.needsNameReview) {
                    Text("旧版本名称过长，完整原值仍保留。")
                    TextButton(onClick = {
                        controller.clearVersionNameError()
                        historyOpen = false; versionNameTarget = version; versionName = version.title
                    }) { Text("修正历史版本名称") }
                }
                TextButton(onClick = { historyOpen = false; controller.restoreVersion(version.id) }) {
                    Text("${version.title} · ${java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(version.createdAt))} · 恢复")
                }
            }
        } }, confirmButton = { TextButton(onClick = { historyOpen = false }) { Text("返回") } })
    versionNameTarget?.let { version ->
        InputDialog(title = "修正历史版本名称", onDismiss = {
            if (!editor.saving) { versionNameTarget = null; historyOpen = true; controller.clearVersionNameError() }
        }, actions = {
            TextButton(onClick = { versionNameTarget = null; historyOpen = true },
                enabled = !editor.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
            TextButton(onClick = {
                controller.renameVersion(version.id, versionName) { versionNameTarget = null; historyOpen = true }
            }, enabled = !editor.saving && versionName.isNotBlank() && versionName.length <= MAX_NAME_CHARACTERS,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("保存名称") }
        }) {
            editor.versionNameError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (editor.renamingVersion) Text("正在修正历史版本名称…")
            Text("原名称过长，仅显示前部。确认后只修改这个历史版本的名称，正文、时间和句子关联保持。")
            OutlinedTextField(value = versionName, onValueChange = { versionName = it }, singleLine = true,
                label = { Text("历史版本名称") }, modifier = Modifier.fillMaxWidth())
        }
    }
}
