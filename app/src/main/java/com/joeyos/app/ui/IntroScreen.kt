package com.joeyos.app.ui

import com.joeyos.app.ui.theme.JoeyFont
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joeyos.app.ui.components.FocusColor
import com.joeyos.app.ui.components.FocusWidth
import com.joeyos.app.ui.components.rememberFocusState
import com.joeyos.app.ui.theme.Amber
import com.joeyos.app.ui.theme.AmberSoft
import com.joeyos.app.ui.theme.Background
import com.joeyos.app.ui.theme.TextFaint

/**
 * First-run setup. On the same input layer as the rest of the app: the buttons are real focus
 * targets (A / Select press, D-pad moves), and focus starts on the first step not yet done.
 * Back does nothing here — JoeyOS may already be the home app, so there's nowhere to go back to.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun IntroScreen(
    onGrantAccess: () -> Unit,
    onSetHomeApp: () -> Unit = {},
    onContinue: () -> Unit = {},
    grantDone: Boolean = false,
    homeDone: Boolean = false
) {
    BackHandler { }

    val grantFocus = remember { FocusRequester() }
    val homeFocus = remember { FocusRequester() }
    val continueFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    // Land on the next thing to do; re-land when a step completes (returning from Settings).
    LaunchedEffect(grantDone, homeDone) {
        withFrameNanos { }
        inputMode.requestInputMode(InputMode.Keyboard)
        runCatching {
            when {
                !grantDone -> grantFocus.requestFocus()
                else       -> continueFocus.requestFocus()
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        val w = maxWidth
        val h = maxHeight

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = h * 0.06f)
        ) {
            Text(
                text = "JOEYOS",
                fontSize = 42.sp,
                fontFamily = JoeyFont,
                fontWeight = FontWeight.Bold,
                color = Amber,
                letterSpacing = 8.sp
            )
            Spacer(Modifier.height(h * 0.008f))
            Text(
                text = "RETRO GAME LAUNCHER",
                fontSize = 11.sp,
                fontFamily = JoeyFont,
                fontWeight = FontWeight.Normal,
                color = TextFaint,
                letterSpacing = 4.sp
            )

            Spacer(Modifier.height(h * 0.04f))
            IntroButton(
                label = if (grantDone) "File Access Granted" else "Grant File Access",
                done = grantDone, corner = w * 0.025f, padV = h * 0.02f,
                onClick = onGrantAccess, modifier = Modifier.focusRequester(grantFocus)
            )
            Spacer(Modifier.height(h * 0.012f))
            IntroButton(
                label = if (homeDone) "Home App Set" else "Set as Home App  (optional)",
                done = homeDone, corner = w * 0.025f, padV = h * 0.02f,
                onClick = onSetHomeApp, modifier = Modifier.focusRequester(homeFocus)
            )
            if (grantDone) {
                Spacer(Modifier.height(h * 0.012f))
                IntroButton(
                    label = "Continue", done = false, corner = w * 0.025f, padV = h * 0.02f,
                    onClick = onContinue, modifier = Modifier.focusRequester(continueFocus)
                )
            }
        }
    }
}

/** One setup step: amber-ringed when focused; greyed out (and not a stop) once done. */
@Composable
private fun IntroButton(
    label: String,
    done: Boolean,
    corner: Dp,
    padV: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (source, focused) = rememberFocusState()
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (done) Color.White.copy(alpha = 0.04f) else AmberSoft)
            .border(
                width = if (focused) FocusWidth else 1.dp,
                color = when {
                    focused -> FocusColor
                    done    -> Color.White.copy(alpha = 0.12f)
                    else    -> Amber.copy(alpha = 0.6f)
                },
                shape = shape
            )
            .clickable(enabled = !done, interactionSource = source, indication = null, onClick = onClick)
            .padding(vertical = padV),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontFamily = JoeyFont,
            fontWeight = FontWeight.Bold,
            color = if (done) TextFaint else Amber,
            letterSpacing = 1.sp
        )
    }
}
