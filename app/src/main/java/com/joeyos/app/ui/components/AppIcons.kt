package com.joeyos.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * App icons drawn once at the size they're shown and kept, shared by the dock, the App Drawer
 * and the second screen's app grid. Before this each tile drew the icon's full-size drawable
 * into a new bitmap every time it came into view (scrolling the drawer redid it over and over),
 * and an adaptive icon's full size is far bigger than a 52dp tile.
 */
object AppIcons {
    // Sized in bytes: 1/16 of what the app may use, a few MB, room for hundreds of small icons.
    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 16).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private fun key(packageName: String, sizePx: Int) = "$packageName@$sizePx"

    /** The icon if it has already been drawn at this size, else null. Cheap, safe on the main thread. */
    fun cached(packageName: String, sizePx: Int): ImageBitmap? = cache.get(key(packageName, sizePx))

    /** Draws [packageName]'s icon into a [sizePx] square and caches it. Call off the main thread. */
    fun load(context: Context, packageName: String, sizePx: Int): ImageBitmap? {
        cached(packageName, sizePx)?.let { return it }
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(android.graphics.Canvas(bmp))
            bmp.asImageBitmap().also { cache.put(key(packageName, sizePx), it) }
        } catch (e: Exception) { null }
    }

    /** Drops every cached size of [packageName], e.g. after the app was updated or removed. */
    fun evict(packageName: String) {
        val prefix = "$packageName@"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }
}

/**
 * [packageName]'s icon for a tile [size] across. A cached icon is there on the first frame (no
 * placeholder flash when a tile scrolls back into view); otherwise it's null, the caller's
 * placeholder shows, and the icon is drawn on a background thread.
 */
@Composable
fun rememberAppIcon(packageName: String, size: Dp): State<ImageBitmap?> {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.toPx() }.roundToInt().coerceAtLeast(1)
    val initial = remember(packageName, sizePx) { AppIcons.cached(packageName, sizePx) }
    return produceState(initial, packageName, sizePx) {
        // produceState keeps the old value when its keys change, so start from the right one.
        value = initial
        if (initial == null) {
            value = withContext(Dispatchers.IO) { AppIcons.load(context.applicationContext, packageName, sizePx) }
        }
    }
}
