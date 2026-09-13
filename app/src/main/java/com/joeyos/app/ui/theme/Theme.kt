package com.joeyos.app.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary         = Accent,
    background      = Background,
    surface         = Surface,
    onBackground    = TextPrimary,
    onSurface       = TextPrimary,
    onPrimary       = Background,
)

/**
 * How a list scrolls to show the item the D-pad moves to. Android's default scrolls just far
 * enough to show the item itself, so a heading or line of text right above or below it stayed off
 * screen (found on device: Up to the dock-size row hid "Dock icon size" and its preview). This
 * keeps a margin, a fifth of the list's height, clear on both sides of the focused item, the TV
 * way; at the very top or bottom the list just stops, so what's above the first row shows.
 */
@OptIn(ExperimentalFoundationApi::class)
private val MarginBringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = containerSize * 0.2f
        val leading = offset - margin
        val trailing = offset + size + margin - containerSize
        return when {
            size + 2 * margin > containerSize -> if (offset < 0 || offset + size > containerSize) offset else 0f
            leading < 0 -> leading
            trailing > 0 -> trailing
            else -> 0f
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JoeyOSTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = Typography
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides MarginBringIntoViewSpec, content = content)
    }
}
