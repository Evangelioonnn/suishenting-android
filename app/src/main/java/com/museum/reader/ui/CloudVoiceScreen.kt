@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.museum.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.museum.reader.cloud.BAIDU_VOICES
import com.museum.reader.cloud.CloudProvider
import com.museum.reader.cloud.CloudSpeechPreferences
import com.museum.reader.cloud.CloudVoice
import com.museum.reader.cloud.XUNFEI_VOICES

@Composable
internal fun CloudVoiceScreen(
    ui: AppUiState,
    onBack: () -> Unit,
    onPreferences: (CloudSpeechPreferences) -> Unit,
    onSaveXunfei: (String, String, String) -> Unit,
    onSaveBaidu: (String, String, String) -> Unit,
    onClearCredentials: (CloudProvider) -> Unit,
    onPreview: (CloudProvider, String) -> Unit,
    onGenerate: () -> Unit,
    onCancelGeneration: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val cloud = ui.cloudSpeech
    val preferences = cloud.preferences
    var xunfeiAppId by remember { mutableStateOf("") }
    var xunfeiApiKey by remember { mutableStateOf("") }
    var xunfeiApiSecret by remember { mutableStateOf("") }
    var baiduAppId by remember { mutableStateOf("") }
    var baiduApiKey by remember { mutableStateOf("") }
    var baiduSecretKey by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("自然联网声音") },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f),
                ) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("使用自然声音缓存", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "联网生成一次后保存在手机里；没生成或服务异常时，自动使用系统声音。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = preferences.enabled,
                            onCheckedChange = { onPreferences(preferences.copy(enabled = it)) },
                        )
                    }
                }
            }
            item {
                SettingsCard {
                    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("今日费用保护", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Text("${cloud.usedToday}/${preferences.dailyCharacterLimit} 字", color = MaterialTheme.colorScheme.primary)
                        }
                        LinearProgressIndicator(
                            progress = { (cloud.usedToday.toFloat() / preferences.dailyCharacterLimit).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5_000, 20_000, 50_000).forEach { limit ->
                                val selected = preferences.dailyCharacterLimit == limit
                                if (selected) {
                                    Button(onClick = { onPreferences(preferences.copy(dailyCharacterLimit = limit)) },
                                        modifier = Modifier.weight(1f).semantics { this.selected = true }) {
                                        Icon(Icons.Rounded.Check, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (limit >= 10_000) "${limit / 10_000} 万" else "${limit / 1000} 千")
                                    }
                                } else {
                                    OutlinedButton(onClick = { onPreferences(preferences.copy(dailyCharacterLimit = limit)) },
                                        modifier = Modifier.weight(1f).semantics { this.selected = false }) {
                                        Text(if (limit >= 10_000) "${limit / 10_000} 万" else "${limit / 1000} 千")
                                    }
                                }
                            }
                        }
                        Text("本机统计收到的合成结果；取消或超时的请求也可能产生服务费用，实际额度和计费以服务商控制台为准。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Text("主服务与备用服务", style = MaterialTheme.typography.headlineSmall)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CloudProviderChoice(
                        provider = CloudProvider.XUNFEI,
                        configured = cloud.xunfeiConfigured,
                        selected = preferences.primaryProvider == CloudProvider.XUNFEI,
                        onClick = { onPreferences(preferences.copy(primaryProvider = CloudProvider.XUNFEI)) },
                        modifier = Modifier.weight(1f),
                    )
                    CloudProviderChoice(
                        provider = CloudProvider.BAIDU,
                        configured = cloud.baiduConfigured,
                        selected = preferences.primaryProvider == CloudProvider.BAIDU,
                        onClick = { onPreferences(preferences.copy(primaryProvider = CloudProvider.BAIDU)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                CredentialCard(
                    title = "讯飞开放平台密钥",
                    configured = cloud.xunfeiConfigured,
                    labels = listOf("APPID", "APIKey", "APISecret"),
                    values = listOf(xunfeiAppId, xunfeiApiKey, xunfeiApiSecret),
                    onValues = { values ->
                        xunfeiAppId = values[0]; xunfeiApiKey = values[1]; xunfeiApiSecret = values[2]
                    },
                    onSave = {
                        onSaveXunfei(xunfeiAppId, xunfeiApiKey, xunfeiApiSecret)
                        xunfeiAppId = ""; xunfeiApiKey = ""; xunfeiApiSecret = ""
                    },
                    onClear = { onClearCredentials(CloudProvider.XUNFEI) },
                )
            }
            item {
                Text("讯飞声音", style = MaterialTheme.typography.titleLarge)
            }
            items(XUNFEI_VOICES, key = { "xf-${it.id}" }) { voice ->
                CloudVoiceChoice(
                    voice = voice,
                    selected = preferences.xunfeiVoice == voice.id,
                    configured = cloud.xunfeiConfigured,
                    onSelect = { onPreferences(preferences.copy(xunfeiVoice = voice.id)) },
                    onPreview = { onPreview(CloudProvider.XUNFEI, voice.id) },
                )
            }
            item {
                CredentialCard(
                    title = "百度智能云密钥",
                    configured = cloud.baiduConfigured,
                    labels = listOf("AppID", "API Key", "Secret Key"),
                    values = listOf(baiduAppId, baiduApiKey, baiduSecretKey),
                    onValues = { values ->
                        baiduAppId = values[0]; baiduApiKey = values[1]; baiduSecretKey = values[2]
                    },
                    onSave = {
                        onSaveBaidu(baiduAppId, baiduApiKey, baiduSecretKey)
                        baiduAppId = ""; baiduApiKey = ""; baiduSecretKey = ""
                    },
                    onClear = { onClearCredentials(CloudProvider.BAIDU) },
                )
            }
            item {
                Text("百度声音", style = MaterialTheme.typography.titleLarge)
            }
            items(BAIDU_VOICES, key = { "bd-${it.id}" }) { voice ->
                CloudVoiceChoice(
                    voice = voice,
                    selected = preferences.baiduVoice == voice.id,
                    configured = cloud.baiduConfigured,
                    onSelect = { onPreferences(preferences.copy(baiduVoice = voice.id)) },
                    onPreview = { onPreview(CloudProvider.BAIDU, voice.id) },
                )
            }
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("为当前文稿生成", style = MaterialTheme.typography.titleLarge)
                        Text(
                            ui.currentDocument?.let { "《${it.title}》共 ${ui.content.sentences.size} 句。主服务失败时会自动尝试备用服务。" }
                                ?: "先打开一篇讲解词，再到这里生成自然声音。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (cloud.generationTotal > 0) {
                            LinearProgressIndicator(
                                progress = { cloud.generationDone.toFloat() / cloud.generationTotal.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("${cloud.generationDocumentTitle} · 正在处理 ${cloud.generationDone}/${cloud.generationTotal} 句")
                            OutlinedButton(onClick = onCancelGeneration, modifier = Modifier.fillMaxWidth()) { Text("停止生成") }
                        } else {
                            Button(
                                onClick = onGenerate,
                                enabled = preferences.enabled && ui.currentDocument != null &&
                                    (cloud.xunfeiConfigured || cloud.baiduConfigured),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                shape = RoundedCornerShape(18.dp),
                            ) { Text("生成并缓存自然声音") }
                        }
                    }
                }
            }
            item {
                Text(
                    (cloud.credentialError?.let { it + "\n\n" } ?: "") + "安全说明：密钥由 Android Keystore 加密后仅保存在这台手机，备份文件不会包含密钥。请不要把密钥发到聊天或截图中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun CloudProviderChoice(
    provider: CloudProvider,
    configured: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(provider.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                if (configured) "已配置${if (selected) " · 主服务" else " · 备用"}" else "尚未配置",
                style = MaterialTheme.typography.bodyMedium,
                color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CredentialCard(
    title: String,
    configured: Boolean,
    labels: List<String>,
    values: List<String>,
    onValues: (List<String>) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    if (configured) "已安全保存" else "未配置",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (configured) "留空不会显示旧密钥；填写三项并保存会整体替换。" else "请从服务商控制台复制三项参数。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            labels.forEachIndexed { index, label ->
                OutlinedTextField(
                    value = values[index],
                    onValueChange = { next -> onValues(values.toMutableList().also { it[index] = next }) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(label) },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onSave,
                    enabled = values.all { it.isNotBlank() },
                    modifier = Modifier.weight(1f),
                ) { Text(if (configured) "替换密钥" else "安全保存") }
                if (configured) {
                    TextButton(onClick = onClear) { Text("清除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun CloudVoiceChoice(
    voice: CloudVoice,
    selected: Boolean,
    configured: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(voice.label, style = MaterialTheme.typography.titleMedium)
                Text(voice.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
            TextButton(onClick = onPreview, enabled = configured) { Text("试听") }
        }
    }
}
