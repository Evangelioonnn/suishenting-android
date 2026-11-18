package com.museum.reader.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A compact window can scroll the whole form; long import previews can keep their actions in reach. */
@Composable
internal fun InputDialog(
    title: String,
    onDismiss: () -> Unit,
    scrollResetKey: Any? = null,
    pinActions: Boolean = false,
    actions: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .semantics { paneTitle = title }
                    .pointerInput(Unit) { detectTapGestures {} },
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                val form: @Composable ColumnScope.() -> Unit = {
                    Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    content()
                }
                val buttons: @Composable (Modifier) -> Unit = { modifier ->
                    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
                }
                if (pinActions) {
                    PinnedDialogForm(scrollResetKey, form, buttons)
                } else {
                    val scroll = rememberScrollState()
                    LaunchedEffect(scrollResetKey) { scroll.scrollTo(0) }
                    Column(Modifier.verticalScroll(scroll).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        form()
                        buttons(Modifier)
                    }
                }
            }
        }
    }
}

/** Keep the same input nodes when the keyboard changes the available window height. */
@Composable
internal fun PinnedDialogForm(
    scrollResetKey: Any?,
    form: @Composable ColumnScope.() -> Unit,
    buttons: @Composable (Modifier) -> Unit,
) {
    val formScroll = rememberScrollState()
    val windowScroll = rememberScrollState()
    LaunchedEffect(scrollResetKey) {
        formScroll.scrollTo(0)
        windowScroll.scrollTo(0)
    }
    BoxWithConstraints {
        val scrollWindow = maxHeight < 180.dp
        Column(if (scrollWindow) Modifier.verticalScroll(windowScroll) else Modifier) {
            val formModifier = if (scrollWindow) Modifier else Modifier.weight(1f, fill = false).verticalScroll(formScroll)
            Column(formModifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), content = form)
            buttons(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }
    }
}
