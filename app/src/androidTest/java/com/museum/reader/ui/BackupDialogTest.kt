package com.museum.reader.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import com.museum.reader.MainActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.museum.reader.data.BackupConflictPolicy
import com.museum.reader.data.BackupPreview
import com.museum.reader.ui.theme.MuseumReaderTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupDialogTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun partialRestoreRequiresExplicitAcknowledgment() {
        var confirmed = false
        compose.runOnUiThread { compose.activity.setContent {
            MuseumReaderTheme {
                BackupRestoreDialog(BackupPreview(1, 0, emptyList(), "test",
                    warnings = listOf("示例文稿：音频缺失")), {}, { _, _ -> confirmed = true })
            }
        } }
        compose.onNodeWithText("确认恢复").assertIsNotEnabled()
        compose.onNodeWithText("已了解，将只恢复校验通过的内容").performScrollTo().performClick()
        compose.onNodeWithText("确认恢复").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(confirmed) }
    }

    @Test fun replacementRequiresExplicitChoiceAndConfirmation() {
        var chosen: BackupConflictPolicy? = null
        compose.runOnUiThread { compose.activity.setContent {
            MuseumReaderTheme {
                BackupRestoreDialog(BackupPreview(0, 0, listOf("冲突文稿"), "test"), {}, { policy, _ -> chosen = policy })
            }
        } }
        compose.onNodeWithText("确认恢复").assertIsEnabled()
        compose.onNodeWithText("覆盖并保留恢复点").performScrollTo().performClick()
        compose.onNodeWithText("确认恢复").assertIsNotEnabled()
        compose.onNodeWithText("确认以备份内容覆盖冲突文稿").performScrollTo().performClick()
        compose.onNodeWithText("确认恢复").performClick()
        compose.runOnIdle { assertEquals(BackupConflictPolicy.REPLACE, chosen) }
    }
}
