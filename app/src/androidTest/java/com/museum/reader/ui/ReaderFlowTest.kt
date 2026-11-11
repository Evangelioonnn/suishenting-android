package com.museum.reader.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import com.museum.reader.MainActivity
import com.museum.reader.domain.TextSegmenter
import com.museum.reader.model.*
import com.museum.reader.ui.theme.MuseumReaderTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun searchAndBookmarkBrowseDoNotSeekPlaybackUntilExplicitlyRequested() {
        val content = TextSegmenter.segment("第一句。目标内容。\n\n其他句。目标内容。")
        val doc = ReaderDocument("viewed", "测试文稿", DocumentSource.TXT, content.normalizedText, 1, 1)
        var seeks = 0; var starts = -1
        compose.runOnUiThread { compose.activity.setContent {
            MuseumReaderTheme {
                ReaderScreen(ui = AppUiState(currentDocument = doc, content = content, isBusy = false),
                    playback = PlaybackSnapshot(documentId = doc.id, sentences = content.sentences.map { it.text },
                        currentIndex = 0, status = PlaybackStatus.PLAYING),
                    onBack = {}, onSettings = {}, onEdit = {}, onSentence = { seeks++ },
                    onParagraph = {}, onPlayPause = {}, onPrevious = {}, onNext = {}, onStop = {},
                    onLoopMode = {}, onCancelRange = {}, onBookmark = {}, onRecording = {},
                    onStartAt = { starts = it }, onReadAll = {}, onRate = {}, onTimer = {}, onRouteMove = {},
                    snackbar = remember { SnackbarHostState() })
            }
        } }
        compose.onNodeWithContentDescription("搜索正文").performClick()
        val query = compose.onNode(hasSetTextAction())
        query.performClick().performTextInput("目标")
        query.performImeAction()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("查找正文").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("1/2").fetchSemanticsNodes().isNotEmpty() &&
                compose.onNodeWithText("1/2").isDisplayed()
        }
        compose.onNodeWithContentDescription("下一处").performClick()
        compose.onNodeWithText("2/2").assertExists()
        assertEquals(0, seeks)
        assertEquals(-1, starts)
        compose.onNodeWithContentDescription("从命中句朗读").performClick()
        assertEquals(3, starts)
        assertEquals(0, seeks)
    }
}
