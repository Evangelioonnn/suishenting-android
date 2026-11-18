package com.museum.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import com.museum.reader.MainActivity
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class InputDialogFocusTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun resizingAcrossTheScrollThresholdKeepsTheFocusedEditorAndItsText() {
        var compact by mutableStateOf(false)
        compose.runOnUiThread { compose.activity.setContent {
            Box(Modifier.size(320.dp, if (compact) 148.dp else 320.dp)) {
                PinnedDialogForm(null, form = {
                    var value by remember { mutableStateOf("原名") }
                    BasicTextField(value, { value = it }, modifier = Modifier.testTag("name"))
                }, buttons = { Text("确认", modifier = it) })
            }
        } }
        val field = compose.onNodeWithTag("name")
        field.performClick()
        field.performTextReplacement("已经输入")
        field.assertIsFocused()
        compose.runOnIdle { compact = true }
        field.assertIsFocused().assertTextEquals("已经输入")
        field.performTextInput("新字")
        compose.runOnIdle { compact = false }
        field.assertIsFocused().assertTextContains("新字", substring = true)
    }
}
