package com.joeyos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joeyos.app.ui.theme.Amber
import com.joeyos.app.ui.theme.AmberSoft
import com.joeyos.app.ui.theme.Background
import com.joeyos.app.ui.theme.TextFaint

@Composable
fun IntroScreen(
    onGrantAccess: () -> Unit,
    onSetHomeApp: () -> Unit = {},
    onContinue: () -> Unit = {},
    selectedIdx: Int = -1,
    grantDone: Boolean = false,
    homeDone: Boolean = false
) {
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
            // Logo / wordmark
            Text(
                text = "JOEYOS",
                fontSize = 42.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Amber,
                letterSpacing = 8.sp
            )
            Spacer(Modifier.height(h * 0.008f))
            Text(
                text = "RETRO GAME LAUNCHER",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                color = TextFaint,
                letterSpacing = 4.sp
            )

            Spacer(Modifier.height(h * 0.04f))

            // Grant button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(w * 0.025f))
                    .background(if (grantDone) Color.White.copy(alpha = 0.04f) else AmberSoft)
                    .border(
                        width = if (!grantDone && selectedIdx == 0) 2.dp else 1.dp,
                        color = when {
                            grantDone        -> Color.White.copy(alpha = 0.12f)
                            selectedIdx == 0 -> Amber
                            else             -> Amber.copy(alpha = 0.6f)
                        },
                        shape = RoundedCornerShape(w * 0.025f)
                    )
                    .clickable(enabled = !grantDone) { onGrantAccess() }
                    .padding(vertical = h * 0.02f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (grantDone) "File Access Granted" else "Grant File Access",
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (grantDone) TextFaint else Amber,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(h * 0.012f))

            // Set as home app
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(w * 0.025f))
                    .background(if (homeDone) Color.White.copy(alpha = 0.04f) else AmberSoft)
                    .border(
                        width = if (!homeDone && selectedIdx == 1) 2.dp else 1.dp,
                        color = when {
                            homeDone         -> Color.White.copy(alpha = 0.12f)
                            selectedIdx == 1 -> Amber
                            else             -> Amber.copy(alpha = 0.6f)
                        },
                        shape = RoundedCornerShape(w * 0.025f)
                    )
                    .clickable(enabled = !homeDone) { onSetHomeApp() }
                    .padding(vertical = h * 0.02f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (homeDone) "Home App Set" else "Set as Home App  (optional)",
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (homeDone) TextFaint else Amber,
                    letterSpacing = 1.sp
                )
            }

            if (grantDone) {
                Spacer(Modifier.height(h * 0.012f))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(w * 0.025f))
                        .background(AmberSoft)
                        .border(
                            width = if (selectedIdx == 2) 2.dp else 1.dp,
                            color = if (selectedIdx == 2) Amber else Amber.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(w * 0.025f)
                        )
                        .clickable { onContinue() }
                        .padding(vertical = h * 0.02f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Continue",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Amber,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}
