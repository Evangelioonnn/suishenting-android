@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.museum.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import java.util.Locale

@Composable
internal fun SettingsScreen(
    ui: AppUiState,
    onBack: () -> Unit,
    onSpeechRate: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onFontSize: (Float) -> Unit,
    onVoice: (String?) -> Unit,
    onTest: () -> Unit,
    onSentencePause: (Int) -> Unit,
    onParagraphPause: (Int) -> Unit,
    onSaveForDocument: () -> Unit,
    onCloudVoice: () -> Unit,
    onFollowGlobal: () -> Unit,
    onStorage: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    snackbar: SnackbarHostState,
) {
    var helpOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("朗读设置") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                OutlinedButton(onClick = { helpOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("使用帮助与隐私说明")
                }
            }
            item {
                OutlinedButton(onClick = onStorage, modifier = Modifier.fillMaxWidth()) {
                    Text("存储维护、最近删除与恢复点")
                }
            }
            item {
                Text("这里修改全局默认。本文设有专用设置时，继续使用本文设置。播放中的声音从下一句起应用变化。",
                    style = MaterialTheme.typography.bodyMedium)
            }
            item {
                SettingsCard {
                    Text("备份与恢复", style = MaterialTheme.typography.titleLarge)
                    Text("包括文稿、重点句、路线、历史、草稿、恢复区和已保存音频；不包括云密钥。")
                    Button(onClick = onExportBackup, modifier = Modifier.fillMaxWidth()) { Text("导出完整备份") }
                    OutlinedButton(onClick = onImportBackup, modifier = Modifier.fillMaxWidth()) { Text("校验并恢复备份") }
                }
            }
            if (ui.currentDocument != null) item {
                SettingsCard {
                    Text("本文：${ui.currentDocument.title}", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onFollowGlobal) { Text("恢复本文跟随全局默认") }
                }
            }
            item {
                SettingsCard {
                    SettingSlider(
                        title = "语速",
                        valueText = String.format(Locale.SIMPLIFIED_CHINESE, "%.2f 倍", ui.settings.speechRate),
                        value = ui.settings.speechRate,
                        range = 0.55f..1.55f,
                        steps = 19,
                        onValue = onSpeechRate,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingSlider(
                        title = "音调",
                        valueText = String.format(Locale.SIMPLIFIED_CHINESE, "%.2f", ui.settings.pitch),
                        value = ui.settings.pitch,
                        range = 0.7f..1.3f,
                        steps = 11,
                        onValue = onPitch,
                    )
                }
            }
            item {
                SettingsCard {
                    SettingSlider(
                        title = "句间停顿",
                        valueText = String.format(Locale.SIMPLIFIED_CHINESE, "%.1f 秒", ui.settings.sentencePauseMs / 1000f),
                        value = ui.settings.sentencePauseMs.toFloat(),
                        range = 0f..3000f,
                        steps = 11,
                        onValue = { onSentencePause(it.toInt()) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingSlider(
                        title = "段间停顿",
                        valueText = String.format(Locale.SIMPLIFIED_CHINESE, "%.1f 秒", ui.settings.paragraphPauseMs / 1000f),
                        value = ui.settings.paragraphPauseMs.toFloat(),
                        range = 0f..5000f,
                        steps = 19,
                        onValue = { onParagraphPause(it.toInt()) },
                    )
                }
            }
            item {
                SettingsCard {
                    VoiceSelector(ui, onVoice)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingSlider(
                        title = "正文字号",
                        valueText = "${ui.settings.fontSizeSp.toInt()} 号",
                        value = ui.settings.fontSizeSp,
                        range = 17f..28f,
                        steps = 10,
                        onValue = onFontSize,
                    )
                }
            }
            item {
                Button(
                    onClick = onTest,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("试听当前声音")
                }
            }
            if (ui.currentDocument != null) {
                item {
                    OutlinedButton(
                        onClick = onSaveForDocument,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("将全局声音设置用于本文")
                    }
                }
            }
            item {
                Surface(
                    onClick = onCloudVoice,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f),
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Rounded.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text("自然联网声音", style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (ui.cloudSpeech.preferences.enabled) "已开启 · 讯飞与百度可互为备用" else "接入讯飞与百度，按句缓存后可离线播放",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Rounded.NavigateNext, null)
                    }
                }
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            when (ui.ttsStatus) {
                                TtsStatus.READY -> "中文朗读已就绪"
                                TtsStatus.LOADING -> "正在检查中文声音"
                                TtsStatus.MISSING -> "需要安装中文声音"
                                TtsStatus.ERROR -> "朗读引擎暂不可用"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            "声音由手机系统提供。若没有中文声音，请在系统的“文字转语音”设置中下载中文语音包；下载后即可全程离线使用。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (ui.ttsStatus == TtsStatus.MISSING || ui.ttsStatus == TtsStatus.ERROR) {
                            FilledTonalButton(onClick = onOpenTtsSettings) {
                                Text("打开系统文字转语音设置")
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (helpOpen) AlertDialog(onDismissRequest = { helpOpen = false }, title = { Text("使用帮助与隐私") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("暂停后从当前句句首继续。暂停时点句子只定位，播放时点句子从句首继续。查看另一篇不切换声音，点播放本文才切换。")
            Text("范围选择先暂停，点起点再点终点，确认后再播放。手动浏览会暂停跟随，点回到当前句恢复。查找只移动浏览位置，点朗读才切换播放。")
            Text("本文倍率可以覆盖全局默认，三类音源均适用，播放中从下一句生效。音调只用于系统声音。定时停止由播放服务管理。")
            Text("草稿已保存表示本机可恢复副本，尚未提交正式版本。保存期间的新输入继续保留。最近删除至少保留 7 天，本人录音不会自动永久清除。")
            Text("录音保留完整首尾，试听确认后才替换原录音。切后台结束采集并尽量保留待确认内容，可在存储维护中处理。")
            Text("文稿默认保存在本机。主动云合成或试听会把相应文字发送给选定服务商。密钥在本机加密保存，不进入备份。取消不能撤回提供商可能已完成的请求，实际计费以服务商为准。")
        } }, confirmButton = { TextButton(onClick = { helpOpen = false }) { Text("返回设置") } })

}

@Composable
private fun SettingSlider(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValue: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onValue, valueRange = range, steps = steps)
    }
}

@Composable
private fun VoiceSelector(ui: AppUiState, onVoice: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = ui.voiceChoices.firstOrNull { it.name == ui.settings.voiceName }
    Box {
        ListItem(
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
            headlineContent = { Text("声音", style = MaterialTheme.typography.titleLarge) },
            supportingContent = {
                Text(selected?.label ?: "跟随系统默认", color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingContent = { Icon(Icons.Rounded.KeyboardArrowDown, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("跟随系统默认") },
                modifier = Modifier.semantics { this.selected = ui.settings.voiceName == null },
                trailingIcon = { if (ui.settings.voiceName == null) Icon(Icons.Rounded.Check, null) },
                onClick = { onVoice(null); expanded = false },
            )
            ui.voiceChoices.forEach { voice ->
                DropdownMenuItem(
                    text = { Text(voice.label) },
                    modifier = Modifier.semantics { this.selected = ui.settings.voiceName == voice.name },
                    trailingIcon = { if (ui.settings.voiceName == voice.name) Icon(Icons.Rounded.Check, null) },
                    onClick = { onVoice(voice.name); expanded = false },
                )
            }
        }
    }
}
