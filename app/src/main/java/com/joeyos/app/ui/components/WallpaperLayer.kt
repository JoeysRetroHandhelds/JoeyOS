package com.joeyos.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.joeyos.app.data.WallpaperState

// ── Preset definitions ──────────────────────────────────────────────────────

data class PresetWallpaper(val id: String, val name: String, val colors: List<Color>)

val PRESET_WALLPAPERS = listOf(
    PresetWallpaper("sunset", "Sunset Arcade",
        listOf(Color(0xFFFF7849), Color(0xFFFF4D8D), Color(0xFF4A1942))),
    PresetWallpaper("space",  "Deep Space",
        listOf(Color(0xFF0A0B1A), Color(0xFF2A1A5E), Color(0xFF1D4ED8))),
    PresetWallpaper("forest", "Forest CRT",
        listOf(Color(0xFF1A3A2A), Color(0xFF0D1F17), Color(0xFF050A08))),
    PresetWallpaper("drive",  "Midnight Drive",
        listOf(Color(0xFF1A1040), Color(0xFF3D1A5C), Color(0xFFC7457A), Color(0xFFFFB347))),
)

// ── Main composable ──────────────────────────────────────────────────────────

@Composable
fun WallpaperLayer(state: WallpaperState, modifier: Modifier = Modifier) {
    when (state) {
        is WallpaperState.Preset -> {
            val preset = remember(state.id) { PRESET_WALLPAPERS.find { it.id == state.id } ?: PRESET_WALLPAPERS.first() }
            // ShaderBrush caches the compiled shader per size, avoiding reallocation on every draw.
            val brush = remember(preset) {
                object : ShaderBrush() {
                    override fun createShader(size: Size) = LinearGradientShader(
                        from   = Offset.Zero,
                        to     = Offset(size.width, size.height),
                        colors = preset.colors
                    )
                }
            }
            Box(modifier = modifier.fillMaxSize().background(brush))
        }
        is WallpaperState.Animated -> AnimatedBlobWallpaper(modifier)
        is WallpaperState.Custom -> Box(modifier = modifier.fillMaxSize().background(Color(0xFF070710))) {
            AsyncImage(
                model              = state.uri,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize()
            )
        }
    }
}

// ── Animated blob wallpaper ──────────────────────────────────────────────────

@Composable
fun AnimatedBlobWallpaper(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "blobs")

    // Blob 1 — red, top-left drift
    val b1x by t.animateFloat(-0.06f, 0.16f,
        infiniteRepeatable(tween(19_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), "b1x")
    val b1y by t.animateFloat(-0.06f, 0.10f,
        infiniteRepeatable(tween(19_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), "b1y")

    // Blob 2 — blue, bottom-right drift
    val b2x by t.animateFloat(0.06f, -0.14f,
        infiniteRepeatable(tween(23_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), "b2x")
    val b2y by t.animateFloat(0.08f, -0.10f,
        infiniteRepeatable(tween(23_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), "b2y")

    // Blob 3 — purple, mid-right drift
    val b3x by t.animateFloat(0f, -0.12f,
        infiniteRepeatable(tween(27_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), "b3x")
    val b3y by t.animateFloat(0f,  0.14f,
        infiniteRepeatable(tween(27_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), "b3y")

    Canvas(
        modifier = modifier.fillMaxSize().blur(55.dp)
    ) {
        drawRect(Color(0xFF070710))
        val r = size.minDimension * 0.75f / 2f

        drawCircle(
            brush  = Brush.radialGradient(listOf(Color(0xAAFF6B6B), Color(0x00FF6B6B)), radius = r),
            radius = r,
            center = Offset(size.width * (0f + b1x), size.height * (0f + b1y))
        )
        drawCircle(
            brush  = Brush.radialGradient(listOf(Color(0xAA4F8CFF), Color(0x004F8CFF)), radius = r),
            radius = r,
            center = Offset(size.width * (1f + b2x), size.height * (1f + b2y))
        )
        drawCircle(
            brush  = Brush.radialGradient(listOf(Color(0xAAB266FF), Color(0x00B266FF)), radius = r),
            radius = r,
            center = Offset(size.width * (0.65f + b3x), size.height * (0.28f + b3y))
        )
    }
}
