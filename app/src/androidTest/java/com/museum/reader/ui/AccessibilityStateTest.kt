package com.museum.reader.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.museum.reader.MainActivity
import com.museum.reader.cloud.*
import com.museum.reader.domain.TextSegmenter
import com.museum.reader.model.*
import com.museum.reader.ui.theme.MuseumReaderTheme
import org.junit.Rule
import org.junit.Test

class AccessibilityStateTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun pausedSentenceAndLoopChoicesExposeTheirActualState() {
        val raw = TextSegmenter.segment("第一句。第二句。")
        val content = raw.copy(paragraphs = raw.paragraphs.map { p ->
            p.copy(sentences = p.sentences.map { it.copy(id = "s${it.index}") })
        })
        val doc = ReaderDocument("semantics", "状态测试", DocumentSource.TXT, content.normalizedText, 1, 1)
        var ui by mutableStateOf(AppUiState(currentDocument = doc, content = content, isBusy = false,
            loopMode = LoopMode.RANGE, rangeStart = 0, rangeEnd = 0,
            bookmarks = listOf(ReaderBookmark("b", doc.id, "s0", 0, "第一句。", 1))))
        var playback by mutableStateOf(PlaybackSnapshot(documentId = doc.id, status = PlaybackStatus.PAUSED,
            sentences = content.sentences.map { it.text }, loopMode = LoopMode.RANGE, rangeStart = 0, rangeEnd = 0))
        compose.runOnUiThread { compose.activity.setContent {
            MuseumReaderTheme {
                ReaderScreen(ui = ui, playback = playback,
                    onBack = {}, onSettings = {}, onEdit = {},
                    onSentence = { playback = playback.copy(currentIndex = it) },
                    onParagraph = {}, onPlayPause = {}, onPrevious = {}, onNext = {}, onStop = {},
                    onLoopMode = { ui = ui.copy(loopMode = it); playback = playback.copy(loopMode = it) },
                    onCancelRange = {}, onBookmark = {}, onRecording = {}, onStartAt = {}, onReadAll = {},
                    onRate = {}, onTimer = {}, onRouteMove = {}, snackbar = remember { SnackbarHostState() })
            }
        } }
        compose.onNodeWithText("★ 第一句。").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "当前句，循环范围内，重点句"))
        compose.onNodeWithText("第二句。").assertIsNotSelected().performClick()
        compose.onNodeWithText("第二句。").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "当前句"))
        compose.onNodeWithText("★ 第一句。").assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "循环范围内，重点句"))
        compose.onNodeWithText("更多").performClick()
        compose.onNodeWithText("循环方式").performClick()
        val loopOption = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        compose.onNode(hasText("选择范围") and loopOption).performScrollTo().assertIsSelected()
        compose.onNode(hasText("全文循环") and loopOption).performScrollTo().assertIsNotSelected().performClick()
        compose.onNodeWithText("更多").performClick()
        compose.onNodeWithText("循环方式").performClick()
        compose.onNode(hasText("全文循环") and loopOption).assertIsSelected()
        compose.onNode(hasText("不循环") and loopOption).assertIsNotSelected()
    }

    @Test fun cloudProviderVoiceAndBudgetSelectionUpdateAfterChoice() {
        var ui by mutableStateOf(AppUiState(isBusy = false))
        compose.runOnUiThread { compose.activity.setContent {
            MuseumReaderTheme {
                CloudVoiceScreen(ui = ui, onBack = {}, onPreferences = { ui = ui.copy(cloudSpeech = ui.cloudSpeech.copy(preferences = it)) },
                    onSaveXunfei = { _, _, _ -> }, onSaveBaidu = { _, _, _ -> }, onClearCredentials = {},
                    onPreview = { _, _ -> }, onGenerate = {}, onCancelGeneration = {}, snackbar = remember { SnackbarHostState() })
            }
        } }
        choice("2 万").assertIsSelected()
        choice("5 千").assertIsNotSelected().performClick()
        choice("5 千").assertIsSelected()
        choice("2 万").assertIsNotSelected()
        choice("讯飞开放平台").assertIsSelected()
        choice("百度智能云").assertIsNotSelected().performClick()
        choice("百度智能云").assertIsSelected()
        choice("讯飞开放平台").assertIsNotSelected()
        choice("讯飞小燕").assertIsSelected()
        choice("讯飞许久").assertIsNotSelected().performClick()
        choice("讯飞许久").assertIsSelected()
        choice("讯飞小燕").assertIsNotSelected()
        choice("度小美").assertIsSelected()
        choice("度小宇").assertIsNotSelected().performClick()
        choice("度小宇").assertIsSelected()
        choice("度小美").assertIsNotSelected()
    }

    @Test fun systemVoiceAndDefaultExposeSelectionInMenu() {
        var ui by mutableStateOf(AppUiState(isBusy = false,
            voiceChoices = listOf(VoiceChoice("test-voice", "测试系统音色"))))
        compose.runOnUiThread { compose.activity.setContent {
            MuseumReaderTheme {
                SettingsScreen(ui = ui, onBack = {}, onSpeechRate = {}, onPitch = {}, onFontSize = {},
                    onVoice = { ui = ui.copy(settings = ui.settings.copy(voiceName = it)) }, onTest = {},
                    onSentencePause = {}, onParagraphPause = {}, onSaveForDocument = {}, onCloudVoice = {},
                    onFollowGlobal = {}, onStorage = {}, onExportBackup = {}, onImportBackup = {},
                    onOpenTtsSettings = {}, snackbar = remember { SnackbarHostState() })
            }
        } }
        choice("声音").performClick()
        val popup = hasAnyAncestor(isPopup())
        compose.onNode(hasText("跟随系统默认") and popup).assertIsSelected()
        compose.onNode(hasText("测试系统音色") and popup).assertIsNotSelected().performClick()
        choice("声音").performClick()
        compose.onNode(hasText("测试系统音色") and popup).assertIsSelected()
        compose.onNode(hasText("跟随系统默认") and popup).assertIsNotSelected().performClick()
        choice("声音").performClick()
        compose.onNode(hasText("跟随系统默认") and popup).assertIsSelected()
    }

    @Test fun searchKeyboardActionConfirmsCurrentQuery() {
        var query by mutableStateOf("")
        var open by mutableStateOf(true)
        compose.runOnUiThread { compose.activity.setContent {
            MuseumReaderTheme {
                if (open) TextSearchDialog(query, { query = it }, { open = false }, { open = false })
                else Text("查找：$query")
            }
        } }
        compose.onNodeWithText("查看结果").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("目标")
        compose.onNodeWithText("查看结果").assertIsEnabled()
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNodeWithText("查找：目标").assertExists()
    }

    private fun choice(text: String): SemanticsNodeInteraction {
        val matcher = hasText(text) and hasClickAction()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        return compose.onNode(matcher)
    }
}
