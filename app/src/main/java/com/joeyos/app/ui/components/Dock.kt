package com.joeyos.app.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import com.joeyos.app.data.ALL_SYSTEMS
import com.joeyos.app.data.DockBgOpacity
import com.joeyos.app.data.DockCornerStyle
import com.joeyos.app.data.DockTitleSize
import com.joeyos.app.data.DockSortOrder
import com.joeyos.app.data.InstalledApp
import com.joeyos.app.data.RecentGamesReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DockEntry(val packageName: String, val label: String)

const val FAVORITE_PACKAGE   = "com.joeyos.app.FAVORITE"
const val RECENT_ALL_PACKAGE = "com.joeyos.app.RECENT_ALL"

/**
 * Build dock entries by scanning every system's known packages against installed apps.
 * This shows all relevant emulators the user has installed, regardless of assignments.
 * Deduplicates so RetroArch only appears once even though many systems use it.
 * The global recently played tile is always pinned at position 0.
 */
fun buildDockEntries(
    installedApps: List<InstalledApp>,
    lastLaunched: Map<String, Long>,
    sortOrder: DockSortOrder = DockSortOrder.RECENTLY_USED
): List<DockEntry> {
    val seen = mutableSetOf<String>()
    val entries = mutableListOf<Pair<DockEntry, Long>>()
    ALL_SYSTEMS.forEach { sys ->
        sys.knownPackages.forEach { known ->
            val match = installedApps.firstOrNull { it.packageName.startsWith(known) }
            if (match != null && seen.add(match.packageName)) {
                val mostRecent = ALL_SYSTEMS
                    .filter { s -> s.knownPackages.any { match.packageName.startsWith(it) } }
                    .maxOfOrNull { lastLaunched[it.id] ?: Long.MIN_VALUE } ?: Long.MIN_VALUE
                entries += DockEntry(match.packageName, match.label) to mostRecent
            }
        }
    }
    val emulators = when (sortOrder) {
        DockSortOrder.RECENTLY_USED     -> entries.sortedByDescending { it.second }.map { it.first }
        DockSortOrder.ALPHABETICAL      -> entries.sortedBy { it.first.label.lowercase() }.map { it.first }
        DockSortOrder.ALPHABETICAL_DESC -> entries.sortedByDescending { it.first.label.lowercase() }.map { it.first }
    }
    return listOf(
        DockEntry(FAVORITE_PACKAGE,   "Favorite"),
        DockEntry(RECENT_ALL_PACKAGE, "Recent")
    ) + emulators
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Dock(
    dockEntries: List<DockEntry>,
    onEmulatorClick: (packageName: String) -> Unit,
    modifier: Modifier = Modifier,
    favoriteTitle: String? = null,
    selectedIndex: Int = -1,
    onEmulatorLongClick: (packageName: String) -> Unit = {},
    iconSizeDp: Int = 46,
    listState: LazyListState = rememberLazyListState(),
    showBadge: Boolean = true,
    cornerStyle: DockCornerStyle = DockCornerStyle.ROUNDED,
    bgOpacity: DockBgOpacity = DockBgOpacity.NONE,
    titleSize: DockTitleSize = DockTitleSize.MEDIUM
) {
    // Load the most-recently-played title for each emulator, refreshing after every launch.
    var lastPlayedTitles by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    LaunchedEffect(dockEntries) {
        val titles = mutableMapOf<String, String?>()
        dockEntries.forEach { entry ->
            if (entry.packageName != FAVORITE_PACKAGE && entry.packageName != RECENT_ALL_PACKAGE &&
                RecentGamesReader.supportsRecentlyPlayed(entry.packageName)) {
                titles[entry.packageName] = withContext(Dispatchers.IO) {
                    RecentGamesReader.readForPackage(entry.packageName).firstOrNull()?.title
                }
            }
        }
        lastPlayedTitles = titles
    }

    val bgAlpha = when (bgOpacity) {
        DockBgOpacity.NONE   -> 0f
        DockBgOpacity.LOW    -> 0.20f
        DockBgOpacity.MEDIUM -> 0.45f
        DockBgOpacity.HIGH   -> 0.70f
    }

    // Resolve the title for the currently highlighted icon (null = nothing to show)
    val selectedTitle: String? = if (selectedIndex >= 0 && selectedIndex < dockEntries.size && showBadge) {
        val pkg = dockEntries[selectedIndex].packageName
        when (pkg) {
            FAVORITE_PACKAGE   -> favoriteTitle
            RECENT_ALL_PACKAGE -> null
            else               -> lastPlayedTitles[pkg]
        }
    } else null

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = bgAlpha))
    ) {
        LazyRow(
            state          = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier       = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment     = Alignment.Bottom
        ) {
            itemsIndexed(dockEntries, key = { _, e -> e.packageName }) { i, entry ->
                DockIcon(
                    packageName   = entry.packageName,
                    label         = entry.label,
                    sizeDp        = iconSizeDp,
                    isSelected    = selectedIndex == i,
                    favoriteTitle = favoriteTitle,
                    onClick       = { onEmulatorClick(entry.packageName) },
                    onLongClick   = { onEmulatorLongClick(entry.packageName) },
                    cornerStyle   = cornerStyle
                )
            }
        }

        // Title floats centered above the dock, visible only when an icon is highlighted
        if (selectedTitle != null) {
            Text(
                text       = selectedTitle,
                fontSize   = when (titleSize) {
                    DockTitleSize.SMALL  -> 13.sp
                    DockTitleSize.MEDIUM -> 16.sp
                    DockTitleSize.LARGE  -> 20.sp
                },
                color      = Color.White.copy(alpha = 0.95f),
                fontFamily = FontFamily.Monospace,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-38).dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DockIcon(
    packageName: String,
    label: String,
    sizeDp: Int = 46,
    isSelected: Boolean = false,
    favoriteTitle: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    cornerStyle: DockCornerStyle = DockCornerStyle.ROUNDED
) {
    val context   = LocalContext.current
    val cornerDp  = when (cornerStyle) {
        DockCornerStyle.SQUARE  -> 0.dp
        DockCornerStyle.ROUNDED -> (sizeDp * 0.22f).dp
        DockCornerStyle.CIRCLE  -> (sizeDp / 2).dp
    }
    val tileShape = RoundedCornerShape(cornerDp)
    val scale by animateFloatAsState(if (isSelected) 1.18f else 1f, tween(120), label = "dock_scale")

    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val bm = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bm)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bm.asImageBitmap()
            } catch (e: Exception) { null }
        }
    }

    val viewConfig = LocalViewConfiguration.current
    CompositionLocalProvider(
        LocalViewConfiguration provides object : ViewConfiguration by viewConfig {
            override val longPressTimeoutMillis: Long = 250L
        }
    ) {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .scale(scale)
                .clip(tileShape)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                    onClick           = onClick,
                    onLongClick       = onLongClick
                ),
            contentAlignment = Alignment.Center
        ) {
            if (packageName == FAVORITE_PACKAGE) {
                val hasGame = favoriteTitle != null
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                if (hasGame) listOf(Color(0xFFD97706), Color(0xFF451A03))
                                else listOf(Color(0xFF374151), Color(0xFF111827))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (hasGame) {
                        Text(
                            text = favoriteTitle!!.take(2).uppercase(),
                            fontSize = (sizeDp * 0.34f).sp,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    } else {
                        Text("★", fontSize = (sizeDp * 0.42f).sp, color = Color.White.copy(alpha = 0.35f))
                    }
                }
            } else if (packageName == RECENT_ALL_PACKAGE) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF6366F1), Color(0xFF1E1B4B))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("◷", fontSize = (sizeDp * 0.42f).sp, color = Color.White,
                        fontFamily = FontFamily.Default)
                }
            } else if (icon != null) {
                Image(
                    bitmap             = icon!!,
                    contentDescription = label,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.08f)))
            }
        }
    } // end CompositionLocalProvider
}
