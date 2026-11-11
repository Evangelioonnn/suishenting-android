@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.museum.reader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.museum.reader.model.*
import com.museum.reader.domain.TextPositionIndex
import com.museum.reader.domain.TextSearch
import com.museum.reader.domain.TextSearchResult
import kotlinx.coroutines.*
import java.util.Locale

@Composable
internal fun ReaderScreen(
    ui: AppUiState,
    playback: PlaybackSnapshot,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onEdit: () -> Unit,
    onSentence: (Int) -> Unit,
    onParagraph: (Int) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onLoopMode: (LoopMode) -> Unit,
    onCancelRange: () -> Unit,
    onBookmark: (Int) -> Unit,
    onRecording: () -> Unit,
    onStartAt: (Int) -> Unit,
    onReadAll: () -> Unit,
    onRate: (Float?) -> Unit,
    onTimer: (Int?) -> Unit,
    onRouteMove: (Int) -> Unit,
    snackbar: SnackbarHostState,
) {
    val shortWindow = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } < 440.dp
    val titleHeight = if (shortWindow) 56.dp else (64f * LocalDensity.current.fontScale).coerceAtLeast(64f).dp
    var searchOpen by rememberSaveable(ui.currentDocument?.id) { mutableStateOf(false) }
    var query by rememberSaveable(ui.currentDocument?.id) { mutableStateOf("") }
    var queryEditor by rememberSaveable { mutableStateOf(false) }
    var queryDraft by rememberSaveable { mutableStateOf("") }
    var bookmarkOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var hit by remember(query, ui.content) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var loopSheetOpen by remember { mutableStateOf(false) }
    var rateOpen by remember { mutableStateOf(false) }
    var timerOpen by remember { mutableStateOf(false) }
    var following by rememberSaveable(ui.currentDocument?.id) { mutableStateOf(true) }
    val sameSession = playback.documentId == ui.currentDocument?.id
    val prepared = remember(ui.currentDocument, ui.content, ui.viewedIndex, ui.settings, ui.loopMode, ui.rangeStart, ui.rangeEnd) {
        PlaybackSnapshot(
        documentId = ui.currentDocument?.id, documentTitle = ui.currentDocument?.title.orEmpty(),
        sentences = ui.content.sentences.map { it.text }, currentIndex = ui.viewedIndex,
        speechRate = ui.currentDocument?.speechRate, globalSettings = ui.settings,
        loopMode = ui.loopMode, rangeStart = ui.rangeStart, rangeEnd = ui.rangeEnd,
        status = PlaybackStatus.PAUSED) }
    val shown = if (sameSession) playback else prepared
    val layout = ui.readerLayout ?: remember(ui.content) { com.museum.reader.domain.ReaderLayout.build(ui.content) }
    val listState = rememberLazyListState()
    val currentParagraph = ui.content.sentences.getOrNull(shown.currentIndex)?.paragraphIndex ?: 0
    val currentListItem = layout.itemFor(shown.currentIndex)
    val manualScroll = remember(ui.currentDocument?.id) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) following = false
                return Offset.Zero
            }
        }
    }
    val positions by produceState<TextPositionIndex?>(null, ui.content) {
        value = withContext(Dispatchers.Default) {
            TextPositionIndex.stored(ui.content)
        }
    }
    val results by produceState<TextSearchResult?>(null, query, ui.content, searchOpen) {
        value = null
        if (query.isEmpty() || !searchOpen) return@produceState
        delay(150)
        value = withContext(Dispatchers.Default) {
            val job = currentCoroutineContext()
            TextSearch.find(ui.content.normalizedText, query) { job.ensureActive() }
        }
    }
    fun browseSentence(index: Int) {
        following = false
        val sentence = ui.content.sentences.getOrNull(index) ?: return
        scope.launch { listState.animateScrollToItem(layout.itemFor(index)) }
    }
    fun browseHit(index: Int) {
        val found = results ?: return
        if (found.count == 0) return
        hit = Math.floorMod(index, found.count)
        val offset = found.starts[hit]
        val sentence = positions?.sentenceAt(offset) ?: return
        following = false
        scope.launch {
            listState.animateScrollToItem(layout.itemFor(sentence, offset - positions!!.sentenceStarts[sentence]))
        }
    }
    LaunchedEffect(results, positions) { if ((results?.count ?: 0) > 0) browseHit(0) }
    LaunchedEffect(shown.currentIndex, ui.currentDocument?.id, following) {
        if (following && !searchOpen && ui.content.sentences.isNotEmpty())
            listState.animateScrollToItem(currentListItem.coerceAtLeast(0))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                expandedHeight = titleHeight,
                title = {
                    Text(
                        ui.currentDocument?.title.orEmpty(),
                        style = if (shortWindow) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        maxLines = if (shortWindow) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searchOpen = !searchOpen
                        if (searchOpen) { following = false; queryDraft = query; queryEditor = true } else query = ""
                    }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Search, if (searchOpen) "关闭搜索" else "搜索正文")
                    }
                    Box {
                        TextButton(onClick = { moreOpen = true }) { Text("更多") }
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            if (!following) DropdownMenuItem(text = { Text("回到当前句并跟随") }, onClick = {
                                moreOpen = false; following = true
                            })
                            if (searchOpen) DropdownMenuItem(text = { Text("修改查找文字") }, onClick = {
                                moreOpen = false; queryDraft = query; queryEditor = true
                            })
                            DropdownMenuItem(text = { Text("循环方式") }, onClick = { moreOpen = false; loopSheetOpen = true })
                            DropdownMenuItem(text = { Text("本文播放倍率") }, onClick = { moreOpen = false; rateOpen = true })
                            DropdownMenuItem(text = { Text("定时停止") }, onClick = { moreOpen = false; timerOpen = true })
                            DropdownMenuItem(text = { Text("停止播放") }, onClick = { moreOpen = false; onStop() })
                            DropdownMenuItem(text = { Text("编辑本文") }, onClick = { moreOpen = false; onEdit() })
                            DropdownMenuItem(text = { Text("重点句列表") }, onClick = { moreOpen = false; bookmarkOpen = true; following = false })
                            DropdownMenuItem(text = { Text("标记当前句为重点") }, onClick = { moreOpen = false; onBookmark(shown.currentIndex) })
                            DropdownMenuItem(text = { Text("录制本人声音") }, onClick = { moreOpen = false; onRecording() })
                            DropdownMenuItem(text = { Text("声音与显示设置") }, onClick = { moreOpen = false; onSettings() })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            PlayerBar(
                playback = shown,
                loopMode = ui.loopMode,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onStop = onStop,
                onLoop = { loopSheetOpen = true },
                speechRate = shown.effectiveRate,
                selecting = ui.rangeSelectionStage != null,
                compact = shortWindow,
                otherSession = if (!sameSession && playback.documentId != null)
                    playback.documentTitle.take(12) + if (playback.isPlaying) "正在播放" else "已暂停" else null,
                onSpeechRate = { rateOpen = true },
                playLabel = if (!sameSession) "播放本文" else if (shown.isPlaying) "暂停" else "从句首播放",
            )
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            ui.playlists.firstOrNull { it.id == ui.routeContextId }?.let { route ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onRouteMove(-1) }) { Text("上一站") }
                    Text("${route.title} · ${route.documentIds.indexOf(ui.currentDocument?.id) + 1}/${route.documentIds.size}",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onRouteMove(1) }) { Text("下一站") }
                }
                if (sameSession && playback.status == PlaybackStatus.COMPLETED)
                    Text("本文已结束，选择下一站后由你决定播放。", style = MaterialTheme.typography.bodySmall)
            }
            if (!shortWindow && !sameSession && playback.documentId != null) {
                Text("${playback.documentTitle} · ${if (playback.isPlaying) "正在播放" else "已暂停"}，播放本文才会切换",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (ui.rangeSelectionStage != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (ui.rangeSelectionStage == RangeSelectionStage.START) "请选择起点" else "请选择终点（起点第 ${ui.rangeStart + 1} 句）",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onCancelRange) { Text("取消") }
            }
            else if (searchOpen) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (results == null) "查找中…" else if (results!!.count == 0) "0/0"
                    else "${hit + 1}/${results!!.count}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { browseHit(hit - 1) }, enabled = (results?.count ?: 0) > 0,
                    modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.NavigateBefore, "上一处") }
                IconButton(onClick = { browseHit(hit + 1) }, enabled = (results?.count ?: 0) > 0,
                    modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.NavigateNext, "下一处") }
                TextButton(onClick = { positions?.sentenceAt(results!!.starts[hit])?.let(onStartAt) },
                    enabled = (results?.count ?: 0) > 0 && ui.rangeSelectionStage == null,
                    modifier = Modifier.semantics { contentDescription = "从命中句朗读" }) { Text("朗读") }
            } else if (!shortWindow) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!following) TextButton(onClick = { following = true }, modifier = Modifier.weight(1f)) { Text("回到当前句") }
                else Text("跟随当前句", modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { timerOpen = true }, modifier = Modifier.weight(1f)) { Text("定时停止") }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().nestedScroll(manualScroll),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(layout.blocks.size, key = { it }) { item ->
                    val block = layout.blocks[item]
                    if (block.sentence < 0) ParagraphHeader(block.paragraph) { onParagraph(block.paragraph) }
                    else {
                        val sentence = ui.content.sentences[block.sentence]
                        val start = (positions?.sentenceStarts?.getOrNull(sentence.index) ?: -1).let {
                            if (it < 0) -1 else it + block.start
                        }
                        SentenceRow(
                            index = sentence.index, text = sentence.text.substring(block.start, block.end),
                            currentIndex = shown.currentIndex, loopMode = ui.loopMode,
                            rangeStart = ui.rangeStart, rangeEnd = ui.rangeEnd,
                            selecting = ui.rangeSelectionStage != null, fontSize = ui.settings.fontSizeSp,
                            onSentence = onSentence, highlights = highlightRanges(results, start, block.end - block.start),
                            activeHighlight = results?.range(hit)?.let { selected ->
                                val left = maxOf(start, selected.first)
                                val right = minOf(start + block.end - block.start, selected.last + 1)
                                if (start >= 0 && left < right) (left - start) until (right - start) else null
                            },
                            bookmark = block.start == 0 && ui.bookmarks.any { it.sentenceId == sentence.id })
                    }
                }
            }
        }
    }

    if (queryEditor) TextSearchDialog(query = queryDraft, onQuery = { queryDraft = it }, onDismiss = {
        queryEditor = false
        if (query.isEmpty()) searchOpen = false
    }, onConfirm = { query = queryDraft; queryEditor = false },
        explanation = "查找只移动浏览位置，不会跳转播放。")

    if (bookmarkOpen) AlertDialog(onDismissRequest = { bookmarkOpen = false }, title = { Text("本文重点句") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (ui.bookmarks.isEmpty()) Text("还没有重点句。在更多中标记当前句。")
                ui.bookmarks.sortedBy { it.sentenceIndex }.forEach { bookmark ->
                    Text("第 ${bookmark.sentenceIndex + 1} 句 · ${bookmark.text}", maxLines = 3)
                    Row {
                        TextButton(onClick = { bookmarkOpen = false; browseSentence(bookmark.sentenceIndex) }) { Text("定位") }
                        TextButton(onClick = { bookmarkOpen = false; onStartAt(bookmark.sentenceIndex) }) { Text("从该句朗读") }
                        TextButton(onClick = { onBookmark(bookmark.sentenceIndex) }) { Text("取消重点") }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { bookmarkOpen = false }) { Text("返回") } })

    if (rateOpen) AlertDialog(onDismissRequest = { rateOpen = false }, title = { Text("本文播放倍率") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("三类音源均生效；正在播放时从下一句生效。")
                listOf(0.6f, 0.8f, 1f, 1.2f, 1.5f).forEach { rate ->
                    TextButton(onClick = { onRate(rate) }) { Text(String.format(Locale.CHINESE, "%.2f×", rate)) }
                }
                var rate by remember { mutableFloatStateOf(shown.requestedRate) }
                Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.5f..1.6f,
                    onValueChangeFinished = { onRate(rate) })
                Text(String.format(Locale.CHINESE, "本文 %.2f× · 系统音调仅用于系统声音", rate))
                TextButton(onClick = { onRate(null); rateOpen = false }) { Text("恢复跟随全局默认") }
            }
        }, confirmButton = { TextButton(onClick = { rateOpen = false }) { Text("完成") } })
    if (timerOpen) AlertDialog(onDismissRequest = { timerOpen = false }, title = { Text("定时停止") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                playback.timerDeadlineElapsed?.let { deadline ->
                    var remaining by remember { mutableLongStateOf(0L) }
                    LaunchedEffect(deadline) { while (true) {
                        remaining = ((deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
                        delay(1000)
                    } }
                    Text("剩余 ${remaining / 60} 分 ${remaining % 60} 秒")
                }
                listOf(15, 30, 60).forEach { minutes ->
                    TextButton(onClick = { onTimer(minutes); timerOpen = false }) { Text("$minutes 分钟") }
                }
                TextButton(onClick = { onTimer(null); timerOpen = false }) { Text("关闭定时") }
            }
        }, confirmButton = { TextButton(onClick = { timerOpen = false }) { Text("返回") } })

    if (loopSheetOpen) {
        ModalBottomSheet(onDismissRequest = { loopSheetOpen = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("循环方式", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(12.dp))
                LoopChoice("不循环", "从当前位置读到结尾后停止", ui.loopMode == LoopMode.NONE) {
                    onLoopMode(LoopMode.NONE); loopSheetOpen = false
                }
                LoopChoice("全文循环", "读到结尾后从第一句继续", ui.loopMode == LoopMode.ALL) {
                    onLoopMode(LoopMode.ALL); loopSheetOpen = false
                }
                LoopChoice("选择范围", "依次点选起始句和结束句", ui.loopMode == LoopMode.RANGE) {
                    onLoopMode(LoopMode.RANGE); loopSheetOpen = false
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        onReadAll()
                        loopSheetOpen = false
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("从第一句阅读全文")
                }
            }
        }
    }
}

@Composable
private fun ParagraphHeader(
    paragraphIndex: Int,
    onParagraph: () -> Unit,
) {
    TextButton(
        onClick = onParagraph,
        modifier = Modifier.padding(top = 12.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            "第 ${paragraphIndex + 1} 段  ·  从这里开始",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SentenceRow(
    index: Int,
    text: String,
    currentIndex: Int,
    loopMode: LoopMode,
    rangeStart: Int,
    rangeEnd: Int,
    selecting: Boolean,
    fontSize: Float,
    onSentence: (Int) -> Unit,
    highlights: List<IntRange>,
    activeHighlight: IntRange?,
    bookmark: Boolean,
) {
    val inRange = loopMode == LoopMode.RANGE && index in rangeStart..rangeEnd
    val isCurrent = index == currentIndex
    val targetColor = when {
        isCurrent -> MaterialTheme.colorScheme.primaryContainer
        inRange -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    val color by animateColorAsState(targetColor, label = "sentenceHighlight")
    val searchBackground = MaterialTheme.colorScheme.tertiaryContainer
    val searchForeground = MaterialTheme.colorScheme.onTertiaryContainer
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).clickable { onSentence(index) }
            .semantics {
                selected = isCurrent
                stateDescription = buildList {
                    if (isCurrent) add("当前句")
                    if (inRange) add("循环范围内")
                    if (bookmark) add("重点句")
                }.joinToString("，")
            },
        color = color,
        shape = RoundedCornerShape(13.dp),
        border = if (selecting && inRange) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)) else null,
    ) {
        Text(
            buildAnnotatedString {
                if (bookmark) append("★ ")
                val prefix = length
                append(text)
                highlights.forEach { range ->
                    addStyle(SpanStyle(background = searchBackground,
                        color = searchForeground, fontWeight = FontWeight.Bold),
                        prefix + range.first, prefix + range.last + 1)
                }
                activeHighlight?.let { range -> addStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                    prefix + range.first, prefix + range.last + 1) }
            },
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 9.dp),
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.68f).sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun RangeSelectionBanner(stage: RangeSelectionStage?, start: Int, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (stage == RangeSelectionStage.START) "请点选循环的第一句" else "起点为第 ${start + 1} 句，再点选最后一句",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text("也可以点“从这里开始”选择整段起点", style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "取消选择") }
        }
    }
}

@Composable
private fun PlayerBar(
    playback: PlaybackSnapshot, loopMode: LoopMode,
    onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onStop: () -> Unit, onLoop: () -> Unit,
    speechRate: Float, onSpeechRate: () -> Unit, playLabel: String = "播放", selecting: Boolean = false,
    compact: Boolean = false, otherSession: String? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp, tonalElevation = 2.dp) {
        if (compact) Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(buildString {
                append("${playback.currentIndex + 1}/${playback.sentences.size} · ${otherSession ?: playback.outputSource}")
                if (loopMode == LoopMode.RANGE) append(" · 范围 ${playback.rangeStart + 1}–${playback.rangeEnd + 1}")
            }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = onPrevious, enabled = !selecting, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.NavigateBefore, "上一句")
            }
            if (playLabel == "播放本文") Button(onClick = onPlayPause, enabled = !selecting,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp), modifier = Modifier.heightIn(min = 56.dp)) {
                Text("播放本文")
            } else FilledIconButton(onClick = onPlayPause, enabled = !selecting, modifier = Modifier.size(56.dp)) {
                Icon(if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, playLabel)
            }
            IconButton(onClick = onNext, enabled = !selecting, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.NavigateNext, "下一句")
            }
        } else
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(buildString {
                append("${playback.currentIndex + 1}/${playback.sentences.size} 句 · ${playback.outputSource}")
                if (loopMode == LoopMode.RANGE) append(" · 范围 ${playback.rangeStart + 1}–${playback.rangeEnd + 1}")
            }, style = MaterialTheme.typography.bodySmall)
            if (!selecting) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onLoop, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(loopLabel(loopMode)) }
                TextButton(onClick = onSpeechRate, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(String.format(Locale.CHINESE, "倍率 %.2f×", speechRate))
                }
                IconButton(onClick = onStop, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Stop, "停止播放") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onPrevious, enabled = !selecting, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.NavigateBefore, "上一句")
                }
                Button(onClick = onPlayPause, enabled = !selecting, modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                    Icon(if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(playLabel)
                }
                IconButton(onClick = onNext, enabled = !selecting, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.NavigateNext, "下一句")
                }
            }
        }
    }
}

@Composable
private fun LoopChoice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun loopLabel(mode: LoopMode) = when (mode) {
    LoopMode.NONE -> "不循环"
    LoopMode.ALL -> "全文循环"
    LoopMode.RANGE -> "范围循环"
}

private fun highlightRanges(found: TextSearchResult?, start: Int, length: Int): List<IntRange> {
    if (found == null || start < 0 || found.count == 0) return emptyList()
    val search = found.starts.binarySearch((start - found.length + 1).coerceAtLeast(0))
    var at = if (search >= 0) search else -search - 1
    return buildList {
        while (at < found.count && found.starts[at] < start + length) {
            val left = maxOf(start, found.starts[at])
            val right = minOf(start + length, found.starts[at] + found.length)
            if (left < right) add((left - start) until (right - start))
            at++
        }
    }
}
