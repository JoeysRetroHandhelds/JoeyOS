package com.joeyos.app.ui.components

import com.joeyos.app.R
import com.joeyos.app.ui.theme.JoeyFont
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.layout.ContentScale
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
import com.joeyos.app.data.RecentGame
import com.joeyos.app.data.RecentGamesReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DockEntry(val packageName: String, val label: String)

const val FAVORITE_PACKAGE   = "com.joeyos.app.FAVORITE"
const val RECENT_ALL_PACKAGE = "com.joeyos.app.RECENT_ALL"

/**
 * The emulators the dock shows automatically: every system's known packages matched against
 * installed apps, deduplicated so RetroArch appears once even though many systems use it.
 */
fun autoDockPackages(installedApps: List<InstalledApp>): List<InstalledApp> {
    val seen = mutableSetOf<String>()
    val result = mutableListOf<InstalledApp>()
    ALL_SYSTEMS.forEach { sys ->
        sys.knownPackages.forEach { known ->
            val match = installedApps.firstOrNull { it.packageName.startsWith(known) }
            if (match != null && seen.add(match.packageName)) result += match
        }
    }
    return result
}

/**
 * Build dock entries: the automatic emulators (minus any taken off in the App Drawer), plus any
 * apps added from the App Drawer. Favorite and Recent are always first.
 */
fun buildDockEntries(
    installedApps: List<InstalledApp>,
    lastLaunched: Map<String, Long>,
    sortOrder: DockSortOrder = DockSortOrder.RECENTLY_USED,
    pinned: Set<String> = emptySet(),
    hidden: Set<String> = emptySet()
): List<DockEntry> {
    val entries = mutableListOf<Pair<DockEntry, Long>>()
    autoDockPackages(installedApps).filter { it.packageName !in hidden }.forEach { app ->
        val mostRecent = ALL_SYSTEMS
            .filter { s -> s.knownPackages.any { app.packageName.startsWith(it) } }
            .maxOfOrNull { lastLaunched[it.id] ?: Long.MIN_VALUE } ?: Long.MIN_VALUE
        entries += DockEntry(app.packageName, app.label) to mostRecent
    }
    // Added apps have no play history, so in "Recent" order they follow the emulators.
    val present = entries.map { it.first.packageName }.toSet()
    installedApps.filter { it.packageName in pinned && it.packageName !in present }.forEach { app ->
        entries += DockEntry(app.packageName, app.label) to Long.MIN_VALUE
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
    focusedPackage: String? = null,
    onFocusedChange: (String) -> Unit = {},
    focusRequesters: MutableMap<String, FocusRequester> = remember { mutableMapOf() },
    /**
     * False while a page covers the home screen, so the D-pad can't wander onto the dock. A
     * function, read when focus is asked for: the dock is a lazy row whose items only pick up a
     * new plain value at the next layout, after the home screen has already asked for focus back
     * on closing a page, so that request was refused and nothing was highlighted (found on device).
     */
    focusEnabled: () -> Boolean = { true },
    onEmulatorLongClick: (packageName: String) -> Unit = {},
    iconSizeDp: Int = 46,
    listState: LazyListState = rememberLazyListState(),
    showBadge: Boolean = true,
    cornerStyle: DockCornerStyle = DockCornerStyle.ROUNDED,
    bgOpacity: DockBgOpacity = DockBgOpacity.NONE,
    titleSize: DockTitleSize = DockTitleSize.MEDIUM,
    recentGamesVersion: Int = 0
) {
    // Load the most-recently-played title for each emulator, refreshing after every launch.
    // Keyed on recentGamesVersion too, since dockEntries alone doesn't change when the same
    // emulator (already at the top of the dock) plays a different game.
    var lastPlayedTitles by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var recentAllTitle by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(dockEntries, recentGamesVersion) {
        val titles = mutableMapOf<String, String?>()
        var topGame: RecentGame? = null
        dockEntries.forEach { entry ->
            if (entry.packageName != FAVORITE_PACKAGE && entry.packageName != RECENT_ALL_PACKAGE &&
                RecentGamesReader.supportsRecentlyPlayed(entry.packageName)) {
                val game = withContext(Dispatchers.IO) {
                    RecentGamesReader.readForPackage(entry.packageName).firstOrNull()
                }
                titles[entry.packageName] = game?.title
                if (game != null && (topGame == null || game.lastPlayed > topGame!!.lastPlayed)) {
                    topGame = game
                }
            }
        }
        lastPlayedTitles = titles
        recentAllTitle = topGame?.title
    }

    val bgAlpha = when (bgOpacity) {
        DockBgOpacity.NONE   -> 0f
        DockBgOpacity.LOW    -> 0.20f
        DockBgOpacity.MEDIUM -> 0.45f
        DockBgOpacity.HIGH   -> 0.70f
    }

    // Title for the focused icon: its most recent game, or — when there isn't one (an app with
    // no recently played support, or an emulator not played yet) — the app's own name.
    val selectedTitle: String? = if (focusedPackage != null && showBadge) {
        val gameTitle = when (val pkg = focusedPackage) {
            FAVORITE_PACKAGE   -> favoriteTitle
            RECENT_ALL_PACKAGE -> recentAllTitle
            else               -> lastPlayedTitles[pkg]
        }
        gameTitle ?: dockEntries.firstOrNull { it.packageName == focusedPackage }?.label
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
            // Keyed by package, so focus follows the icon itself when the dock re-sorts.
            items(dockEntries, key = { it.packageName }) { entry ->
                DockIcon(
                    packageName   = entry.packageName,
                    label         = entry.label,
                    sizeDp        = iconSizeDp,
                    favoriteTitle = favoriteTitle,
                    focusRequester = focusRequesters.getOrPut(entry.packageName) { FocusRequester() },
                    focusEnabled  = focusEnabled,
                    onFocused     = { onFocusedChange(entry.packageName) },
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
                fontFamily = JoeyFont,
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
    favoriteTitle: String? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    focusEnabled: () -> Boolean = { true },
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    cornerStyle: DockCornerStyle = DockCornerStyle.ROUNDED
) {
    val cornerDp  = when (cornerStyle) {
        DockCornerStyle.SQUARE  -> 0.dp
        DockCornerStyle.ROUNDED -> (sizeDp * 0.22f).dp
        DockCornerStyle.CIRCLE  -> (sizeDp / 2).dp
    }
    val tileShape = RoundedCornerShape(cornerDp)
    // The icon's highlight is its real focus, not a remembered index.
    val interaction = remember { MutableInteractionSource() }
    val isSelected by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isSelected) 1.18f else 1f, tween(120), label = "dock_scale")

    // Drawn at the focused (scaled-up) size so the highlighted icon stays sharp; cached, so
    // scrolling the dock doesn't redraw it.
    val icon by rememberAppIcon(packageName, (sizeDp * 1.18f).dp)

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
                .focusRequester(focusRequester)
                .focusProperties { canFocus = focusEnabled() }
                .onFocusChanged { if (it.isFocused) onFocused() }
                // One clickable node = one focus target. A / Select arrive as DPAD centre.
                .combinedClickable(
                    interactionSource = interaction,
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
                            fontFamily = JoeyFont,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    } else {
                        JoeyIcon(R.drawable.ic_star, Color.White.copy(alpha = 0.35f), (sizeDp * 0.5f).dp)
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
                    JoeyIcon(R.drawable.ic_history, Color.White, (sizeDp * 0.5f).dp)
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
