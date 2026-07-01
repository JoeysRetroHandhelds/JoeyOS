package com.joeyos.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary         = Amber,
    background      = Background,
    surface         = Surface,
    onBackground    = TextPrimary,
    onSurface       = TextPrimary,
    onPrimary       = Background,
)

@Composable
fun JoeyOSTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = Typography,
        content     = content
    )
}
