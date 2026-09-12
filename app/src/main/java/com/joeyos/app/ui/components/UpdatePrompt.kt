package com.joeyos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joeyos.app.data.AppUpdates
import com.joeyos.app.ui.theme.Amber
import com.joeyos.app.ui.theme.TextDim
import com.joeyos.app.ui.theme.TextFaint
import com.joeyos.app.ui.theme.TextPrimary

/** "Update available" popup. Focus lands on Update; left/right moves, A presses, B is Later. */
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
    JoeyDialog(onDismiss = onLater, dismissible = !downloading, initialFocus = updateButton) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.8f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1A1A2E))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                .padding(20.dp)
        ) {
            Text("Update available", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Amber)
            Spacer(Modifier.height(4.dp))
            Text("v$installedVersion  →  v${release.versionName}", fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, color = TextDim)
            if (release.notes.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    release.notes,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextFaint,
                    modifier = Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState())
                )
            }
            Spacer(Modifier.height(16.dp))
            if (downloading) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Amber)
                Spacer(Modifier.height(6.dp))
                Text("Downloading… ${(progress * 100).toInt()}%", fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, color = TextDim)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DialogButton("Update", onUpdate, Modifier.weight(1f).focusRequester(updateButton))
                    DialogButton("Later", onLater, Modifier.weight(1f))
                }
            }
        }
    }
}

/** A dialog button: one clickable node (so one focus target), lit when focused. */
@Composable
fun DialogButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Amber.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
            .border(if (focused) 2.dp else 1.dp,
                if (focused) Amber else Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (focused) Amber else TextPrimary)
    }
}
