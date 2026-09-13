package com.joeyos.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joeyos.app.data.AppUpdates
import com.joeyos.app.ui.theme.Amber
import com.joeyos.app.ui.theme.TextDim
import com.joeyos.app.ui.theme.TextFaint

/**
 * "Update available" popup. It has to be answered: B, Back and a tap outside don't close it, only
 * Update or Later do, so it can't be missed by a stray press (at the user's request). The buttons
 * also ignore presses for a moment after it appears, so a tap or button press meant for whatever
 * was on screen before can't choose for you. Focus lands on Update; left/right moves, A presses.
 */
@Composable
fun UpdatePrompt(
    release: AppUpdates.Release,
    installedVersion: String,
    downloading: Boolean,
    progress: Float,
    onUpdate: () -> Unit,
    onLater: () -> Unit
) {
    val updateButton = remember { FocusRequester() }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(800); armed = true }
    JoeyPopup(title = "Update available", hint = if (downloading) "" else "Choose Update or Later",
        onDismiss = {}, dismissible = false, initialFocus = updateButton) {
        Text("v$installedVersion  →  v${release.versionName}", fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, color = TextDim)
        if (release.notes.isNotBlank()) {
            Text(
                release.notes,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextFaint,
                modifier = Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState())
            )
        }
        if (downloading) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Amber)
            Text("Downloading… ${(progress * 100).toInt()}%", fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, color = TextDim)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                JoeyButton("Update", { if (armed) onUpdate() }, Modifier.weight(1f).focusRequester(updateButton))
                JoeyButton("Later", { if (armed) onLater() }, Modifier.weight(1f))
            }
        }
    }
}
