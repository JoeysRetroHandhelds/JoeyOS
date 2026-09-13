package com.joeyos.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

/**
 * Three soft colour blobs drifting slowly. They stand still while a game runs (the home screen
 * stays resumed behind a game on the other screen, and redrawing it every frame took GPU time
 * from the emulator) and while JoeyOS isn't the app in front. No blur: the gradients already fade
 * to nothing, and a full-screen blur every frame was the expensive part.
 */
@Composable
fun AnimatedBlobWallpaper(modifier: Modifier = Modifier) {
    val playing by com.joeyos.app.data.SecondScreenState.session.collectAsState()
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsState()
    val moving = playing == null && resumed.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    if (moving) MovingBlobs(modifier) else Blobs(modifier, 0.05f, 0.02f, -0.04f, 0f, -0.06f, 0.07f)
}

@Composable
private fun MovingBlobs(modifier: Modifier) {
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

    Blobs(modifier, b1x, b1y, b2x, b2y, b3x, b3y)
}

@Composable
private fun Blobs(modifier: Modifier, b1x: Float, b1y: Float, b2x: Float, b2y: Float, b3x: Float, b3y: Float) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(Color(0xFF070710))
        val r = size.minDimension * 0.75f / 2f

        drawCircle(
            brush  = Brush.radialGradient(listOf(Color(0xAAFF6B6B), Color(0x00FF6B6B)), radius = r,
                center = Offset(size.width * (0f + b1x), size.height * (0f + b1y))),
            radius = r,
            center = Offset(size.width * (0f + b1x), size.height * (0f + b1y))
        )
        drawCircle(
            brush  = Brush.radialGradient(listOf(Color(0xAA4F8CFF), Color(0x004F8CFF)), radius = r,
                center = Offset(size.width * (1f + b2x), size.height * (1f + b2y))),
            radius = r,
            center = Offset(size.width * (1f + b2x), size.height * (1f + b2y))
        )
        drawCircle(
            brush  = Brush.radialGradient(listOf(Color(0xAAB266FF), Color(0x00B266FF)), radius = r,
                center = Offset(size.width * (0.65f + b3x), size.height * (0.28f + b3y))),
            radius = r,
            center = Offset(size.width * (0.65f + b3x), size.height * (0.28f + b3y))
        )
    }
}
