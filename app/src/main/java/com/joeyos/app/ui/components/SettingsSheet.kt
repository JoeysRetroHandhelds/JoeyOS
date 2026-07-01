package com.joeyos.app.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.joeyos.app.data.ALL_SYSTEMS
import com.joeyos.app.data.ClockFormat
import com.joeyos.app.data.DockBgOpacity
import com.joeyos.app.data.DockCornerStyle
import com.joeyos.app.data.DockSortOrder
import com.joeyos.app.data.DockTitleSize
import com.joeyos.app.data.InstalledApp
import com.joeyos.app.data.InfiniteBacklogRepository
import com.joeyos.app.data.RetroAchievementsRepository
import com.joeyos.app.data.RetroArchLauncher
import com.joeyos.app.data.RetroSystem
import com.joeyos.app.data.WallpaperState
import com.joeyos.app.ui.theme.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsSheet(
    wallpaperState: WallpaperState,
    assignments: Map<String, String>,
    installedApps: List<InstalledApp>,
    customWallpapers: List<Uri>,
    dockIconSize: Int,
    dockSortOrder: DockSortOrder = DockSortOrder.RECENTLY_USED,
    onWallpaperChange: (WallpaperState) -> Unit,
    onAddWallpaper: (Uri) -> Unit,
    onRemoveWallpaper: (Uri) -> Unit,
    onAssignmentChange: (systemId: String, packageName: String?) -> Unit,
    onDockIconSizeChange: (Int) -> Unit,
    onDockSortOrderChange: (DockSortOrder) -> Unit = {},
    showRecentBadge: Boolean = true,
    clockFormat: ClockFormat = ClockFormat.H24,
    recentDepth: Int = 20,
    dockBgOpacity: DockBgOpacity = DockBgOpacity.NONE,
    dockTitleSize: DockTitleSize = DockTitleSize.MEDIUM,
    onShowRecentBadgeChange: (Boolean) -> Unit = {},
    onClockFormatChange: (ClockFormat) -> Unit = {},
    onRecentDepthChange: (Int) -> Unit = {},
    onDockBgOpacityChange: (DockBgOpacity) -> Unit = {},
    onDockTitleSizeChange: (DockTitleSize) -> Unit = {},
    onRefreshApps: () -> Unit,
    onDismiss: () -> Unit,
    raRepo: RetroAchievementsRepository,
    ibRepo: InfiniteBacklogRepository,
    selectedTab: Int = 0,
    onTabChange: (Int) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    settingsSelectedIndex: Int = -1,
    onSettingsItemCountChange: (Int) -> Unit = {},
    onSettingsNavGridChange: (List<Int>?) -> Unit = {},
    activateTick: Int = 0,
    dropdownSystemId: String? = null,
    onDropdownSystemChange: (String?) -> Unit = {},
    onDropdownOpen: (Int) -> Unit = {},
    onDropdownClose: () -> Unit = {},
    dropdownSelectedIndex: Int = 0,
    dropdownActivateTick: Int = 0
) {
    val tabs = listOf("Appearance", "Emulators", "Achievements")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SheetBg),
        contentAlignment = Alignment.Center
    ) {
        Column(modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .systemBarsPadding()) {

                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text("JoeyOS", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Amber)
                    TextButton(onClick = onDismiss) {
                        Text("✕", color = TextFaint, fontSize = 18.sp)
                    }
                }

                // Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tabs.forEachIndexed { i, title ->
                        val active = selectedTab == i
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) Amber.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.05f))
                                .border(2.dp,
                                    if (active) Amber else Color.White.copy(0.14f),
                                    RoundedCornerShape(10.dp))
                                .clickable { onTabChange(i) }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(title,
                                color      = if (active) Amber else TextDim,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                                fontSize   = 13.sp
                            )
                        }
                    }
                }

                // Panel
                when (selectedTab) {
                    0 -> AppearancePanel(
                        wallpaperState           = wallpaperState,
                        customWallpapers         = customWallpapers,
                        dockIconSize             = dockIconSize,
                        dockSortOrder            = dockSortOrder,
                        onWallpaperChange        = onWallpaperChange,
                        onAddWallpaper           = onAddWallpaper,
                        onRemoveWallpaper        = onRemoveWallpaper,
                        onSizeChange             = onDockIconSizeChange,
                        onSortOrderChange        = onDockSortOrderChange,
                        showRecentBadge          = showRecentBadge,
                        clockFormat              = clockFormat,
                        recentDepth              = recentDepth,
                        dockBgOpacity            = dockBgOpacity,
                        dockTitleSize            = dockTitleSize,
                        onShowRecentBadgeChange  = onShowRecentBadgeChange,
                        onClockFormatChange      = onClockFormatChange,
                        onRecentDepthChange      = onRecentDepthChange,
                        onDockBgOpacityChange    = onDockBgOpacityChange,
                        onDockTitleSizeChange    = onDockTitleSizeChange,
                        modifier                 = Modifier.weight(1f),
                        listState                = listState,
                        selectedItemIndex        = settingsSelectedIndex,
                        onItemCountChange        = onSettingsItemCountChange,
                        onNavGridChange          = onSettingsNavGridChange,
                        activateTick             = activateTick
                    )
                    1 -> EmulatorsPanel(
                        assignments           = assignments,
                        installedApps         = installedApps,
                        onAssignmentChange    = onAssignmentChange,
                        onRefresh             = onRefreshApps,
                        modifier              = Modifier.weight(1f),
                        listState             = listState,
                        selectedItemIndex     = settingsSelectedIndex,
                        onItemCountChange     = onSettingsItemCountChange,
                        activateTick          = activateTick,
                        dropdownSystemId      = dropdownSystemId,
                        onDropdownSystemChange = onDropdownSystemChange,
                        onDropdownOpen        = onDropdownOpen,
                        onDropdownClose       = onDropdownClose,
                        dropdownSelectedIndex = dropdownSelectedIndex,
                        dropdownActivateTick  = dropdownActivateTick
                    )
                    2 -> RetroAchievementsTab(
                        raRepo            = raRepo,
                        ibRepo            = ibRepo,
                        modifier          = Modifier.weight(1f),
                        selectedItemIndex = settingsSelectedIndex,
                        onItemCountChange = onSettingsItemCountChange,
                        activateTick      = activateTick
                    )
                }
        }
    }
}

@Composable
fun WallpaperTile(
    isActive: Boolean,
    label: String,
    background: Brush,
    modifier: Modifier = Modifier,
    badge: String? = null,
    isNavSelected: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val borderColor = when {
        isActive        -> Amber
        isNavSelected   -> Color.White.copy(alpha = 0.65f)
        else            -> Color.Transparent
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .then(if (isActive || isNavSelected) Modifier.border(2.dp, borderColor, shape) else Modifier)
            .clickable(onClick = onClick)
    ) {
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(badge, fontSize = 9.sp, color = Color.White, fontFamily = FontFamily.Monospace)
            }
        }
        Text(
            text     = label,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            fontSize = 10.5.sp,
            color    = Color.White,
            fontFamily = FontFamily.Monospace
        )
    }
}


// ── Appearance panel ─────────────────────────────────────────────────────────

@Composable
fun AppearancePanel(
    wallpaperState: WallpaperState,
    customWallpapers: List<Uri>,
    dockIconSize: Int,
    dockSortOrder: DockSortOrder = DockSortOrder.RECENTLY_USED,
    onWallpaperChange: (WallpaperState) -> Unit,
    onAddWallpaper: (Uri) -> Unit,
    onRemoveWallpaper: (Uri) -> Unit,
    onSizeChange: (Int) -> Unit,
    onSortOrderChange: (DockSortOrder) -> Unit = {},
    showRecentBadge: Boolean = true,
    clockFormat: ClockFormat = ClockFormat.H24,
    recentDepth: Int = 20,
    dockBgOpacity: DockBgOpacity = DockBgOpacity.NONE,
    dockTitleSize: DockTitleSize = DockTitleSize.MEDIUM,
    onShowRecentBadgeChange: (Boolean) -> Unit = {},
    onClockFormatChange: (ClockFormat) -> Unit = {},
    onRecentDepthChange: (Int) -> Unit = {},
    onDockBgOpacityChange: (DockBgOpacity) -> Unit = {},
    onDockTitleSizeChange: (DockTitleSize) -> Unit = {},
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    selectedItemIndex: Int = -1,
    onItemCountChange: (Int) -> Unit = {},
    onNavGridChange: (List<Int>?) -> Unit = {},
    activateTick: Int = 0
) {
    val steps = listOf(32, 42, 52, 62, 72, 84, 96)
    val currentStep = (steps.indexOfFirst { it >= dockIconSize }.takeIf { it >= 0 } ?: steps.lastIndex)

    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val dir  = java.io.File(context.filesDir, "wallpapers").also { it.mkdirs() }
            val dest = java.io.File(dir, "${System.currentTimeMillis()}.jpg")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.copyTo(dest.outputStream()) }
            }
            if (dest.exists()) onAddWallpaper(android.net.Uri.fromFile(dest))
        }
    }

    // ── Navigation index layout ──────────────────────────────────────────────
    // 0..6:   size step buttons
    // 7:      − button
    // 8:      + button
    // 9..11:  sort order buttons (Recent, A-Z, Z-A)
    // 12..13: recent badge (Show/Hide)
    // 14..16: clock format (24h/12h/Off)
    // 17..19: recent depth (5/10/20)
    // 20..23: dock bg opacity (None/Low/Medium/High)
    // 24..26: dock title size (S/M/L)
    // 27..27+N-1: preset wallpaper tiles
    // 27+N:   animated wallpaper tile
    // 27+N+1..27+N+M: custom wallpaper tiles
    // 27+N+M+1: add wallpaper button
    val N = PRESET_WALLPAPERS.size
    val M = customWallpapers.size

    val numPresetRows = (N + 3) / 4
    val navGrid = remember(N, M) {
        buildList {
            add(7)   // size step buttons
            add(2)   // −/+
            add(3)   // sort orders
            add(2)   // recent badge
            add(3)   // clock format
            add(3)   // recent depth
            add(4)   // dock bg opacity
            add(3)   // dock title size
            var rem = N
            while (rem > 0) { add(minOf(rem, 4)); rem -= 4 }
            add(1)       // animated tile
            add(M + 1)   // custom wallpapers + add button
        }
    }
    LaunchedEffect(navGrid) {
        onItemCountChange(navGrid.sum())
        onNavGridChange(navGrid)
    }

    // Compute lazy-column item index for auto-scroll
    // Lazy layout: 0=size label, 1=preview, 2=step presets, 3=−/+,
    // 4=order label, 5=order buttons, 6=badge, 7=clock, 8=depth, 9=bgOpacity,
    // 10=title size, 11=wallpaper label, 12..12+numPresetRows-1=presets, 12+numPresetRows=anim,
    // 12+numPresetRows+1=custom label, 12+numPresetRows+2=custom row
    //
    // For size / −/+ (0-8): scroll to 0 so the section label and preview are visible above.
    // For sort order (9-11): scroll to 4 so the "DOCK ORDER" label is at top.
    fun navToLazyIdx(navIdx: Int): Int = when {
        navIdx < 9  -> 0    // size steps + −/+: show label and preview
        navIdx < 12 -> 4    // sort order: show "DOCK ORDER" label
        navIdx < 14 -> 6    // recent badge
        navIdx < 17 -> 7    // clock format (24h / 12h / Off)
        navIdx < 20 -> 8    // recent depth
        navIdx < 24 -> 9    // dock bg opacity
        navIdx < 27 -> 10   // dock title size
        navIdx < 27 + N -> 12 + (navIdx - 27) / 4
        navIdx == 27 + N -> 12 + numPresetRows
        else -> 12 + numPresetRows + 2
    }
    LaunchedEffect(selectedItemIndex) {
        if (selectedItemIndex < 0) return@LaunchedEffect
        listState.animateScrollToItem(navToLazyIdx(selectedItemIndex))
    }

    // Keep activation logic up-to-date with current composition values
    val activateRef = remember { object { var action: () -> Unit = {} } }
    SideEffect {
        val idx = selectedItemIndex
        activateRef.action = {
            when {
                idx in 0..6 -> onSizeChange(steps[idx])
                idx == 7    -> onSizeChange(steps[(currentStep - 1).coerceAtLeast(0)])
                idx == 8    -> onSizeChange(steps[(currentStep + 1).coerceAtMost(steps.lastIndex)])
                idx == 9    -> onSortOrderChange(DockSortOrder.RECENTLY_USED)
                idx == 10   -> onSortOrderChange(DockSortOrder.ALPHABETICAL)
                idx == 11   -> onSortOrderChange(DockSortOrder.ALPHABETICAL_DESC)
                idx == 12   -> onShowRecentBadgeChange(true)
                idx == 13   -> onShowRecentBadgeChange(false)
                idx == 14   -> onClockFormatChange(ClockFormat.H24)
                idx == 15   -> onClockFormatChange(ClockFormat.H12)
                idx == 16   -> onClockFormatChange(ClockFormat.HIDDEN)
                idx == 17   -> onRecentDepthChange(5)
                idx == 18   -> onRecentDepthChange(10)
                idx == 19   -> onRecentDepthChange(20)
                idx == 20   -> onDockBgOpacityChange(DockBgOpacity.NONE)
                idx == 21   -> onDockBgOpacityChange(DockBgOpacity.LOW)
                idx == 22   -> onDockBgOpacityChange(DockBgOpacity.MEDIUM)
                idx == 23   -> onDockBgOpacityChange(DockBgOpacity.HIGH)
                idx == 24   -> onDockTitleSizeChange(DockTitleSize.SMALL)
                idx == 25   -> onDockTitleSizeChange(DockTitleSize.MEDIUM)
                idx == 26   -> onDockTitleSizeChange(DockTitleSize.LARGE)
                idx in 27 until 27 + N ->
                    onWallpaperChange(WallpaperState.Preset(PRESET_WALLPAPERS[idx - 27].id))
                idx == 27 + N -> onWallpaperChange(WallpaperState.Animated)
                idx in (27 + N + 1)..(27 + N + M) ->
                    onWallpaperChange(WallpaperState.Custom(customWallpapers[idx - 27 - N - 1]))
                idx == 27 + N + M + 1 -> imagePicker.launch(arrayOf("image/*"))
            }
        }
    }
    LaunchedEffect(activateTick) {
        if (activateTick == 0) return@LaunchedEffect
        activateRef.action()
    }

    fun isNav(navIdx: Int) = selectedItemIndex == navIdx

    LazyColumn(
        state          = listState,
        modifier       = modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
    ) {
        // ── Icon size section ────────────────────────────────────────────
        item {
            Text("DOCK ICON SIZE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(dockIconSize.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFF6B4FA0), Color(0xFF241A3D)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🎮", fontSize = (dockIconSize * 0.42f).sp)
                }
            }
        }

        item {
            // Step presets — navIdx 0..6
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEachIndexed { idx, size ->
                    val active = size == steps[currentStep]
                    val navSel = isNav(idx)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (active || navSel) Amber.copy(alpha = 0.18f)
                                else Color.White.copy(alpha = 0.05f)
                            )
                            .border(1.dp, when {
                                active  -> AmberSoft
                                navSel  -> Color.White.copy(alpha = 0.7f)
                                else    -> Color.White.copy(alpha = 0.12f)
                            }, RoundedCornerShape(9.dp))
                            .clickable { onSizeChange(size) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("$size", fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            color = if (active || navSel) Amber else TextDim)
                    }
                }
            }
        }

        item {
            // −/+ buttons — navIdx 7, 8
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("−" to -1, "+" to 1).forEachIndexed { btnIdx, (label, dir) ->
                    val navSel = isNav(7 + btnIdx)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (navSel) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.06f))
                            .border(1.dp, if (navSel) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                            .clickable {
                                val next = (currentStep + dir).coerceIn(0, steps.lastIndex)
                                onSizeChange(steps[next])
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 22.sp, color = if (navSel) Amber else TextPrimary)
                    }
                }
            }
        }

        // ── Dock order section ───────────────────────────────────────────
        item {
            Spacer(Modifier.height(4.dp))
            Text("DOCK ORDER", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
        }

        item {
            // Sort order buttons — navIdx 9, 10, 11
            val sortOptions = listOf(
                DockSortOrder.RECENTLY_USED     to "Recent",
                DockSortOrder.ALPHABETICAL      to "A-Z",
                DockSortOrder.ALPHABETICAL_DESC to "Z-A"
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sortOptions.forEachIndexed { sortIdx, (order, label) ->
                    val active = dockSortOrder == order
                    val navSel = isNav(9 + sortIdx)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (active || navSel) Amber.copy(alpha = 0.18f)
                                else Color.White.copy(alpha = 0.05f)
                            )
                            .border(1.dp, when {
                                active  -> AmberSoft
                                navSel  -> Color.White.copy(alpha = 0.7f)
                                else    -> Color.White.copy(alpha = 0.12f)
                            }, RoundedCornerShape(9.dp))
                            .clickable { onSortOrderChange(order) }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = if (active || navSel) Amber else TextDim)
                    }
                }
            }
        }

        // ── Recent badge section — navIdx 12..13 ──────────────────────────
        item {
            Spacer(Modifier.height(4.dp))
            Text("RECENT TITLE BADGE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(true to "Show", false to "Hide").forEachIndexed { i, (value, label) ->
                    val active = showRecentBadge == value
                    val navSel = isNav(12 + i)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active || navSel) Amber.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
                            .border(1.dp, when { active -> AmberSoft; navSel -> Color.White.copy(alpha = 0.7f); else -> Color.White.copy(alpha = 0.12f) }, RoundedCornerShape(9.dp))
                            .clickable { onShowRecentBadgeChange(value) }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = if (active || navSel) Amber else TextDim)
                    }
                }
            }
        }

        // ── Clock format section — navIdx 14..15 ──────────────────────────
        item {
            Spacer(Modifier.height(4.dp))
            Text("CLOCK FORMAT", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ClockFormat.H24 to "24h", ClockFormat.H12 to "12h", ClockFormat.HIDDEN to "Off").forEachIndexed { i, (value, label) ->
                    val active = clockFormat == value
                    val navSel = isNav(14 + i)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active || navSel) Amber.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
                            .border(1.dp, when { active -> AmberSoft; navSel -> Color.White.copy(alpha = 0.7f); else -> Color.White.copy(alpha = 0.12f) }, RoundedCornerShape(9.dp))
                            .clickable { onClockFormatChange(value) }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = if (active || navSel) Amber else TextDim)
                    }
                }
            }
        }

        // ── Recent depth section — navIdx 17..19 ──────────────────────────
        item {
            Spacer(Modifier.height(4.dp))
            Text("RECENT LIST DEPTH", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(5 to "5", 10 to "10", 20 to "20").forEachIndexed { i, (value, label) ->
                    val active = recentDepth == value
                    val navSel = isNav(17 + i)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active || navSel) Amber.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
                            .border(1.dp, when { active -> AmberSoft; navSel -> Color.White.copy(alpha = 0.7f); else -> Color.White.copy(alpha = 0.12f) }, RoundedCornerShape(9.dp))
                            .clickable { onRecentDepthChange(value) }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = if (active || navSel) Amber else TextDim)
                    }
                }
            }
        }

        // ── Dock BG opacity section — navIdx 23..26 ───────────────────────
        item {
            Spacer(Modifier.height(4.dp))
            Text("DOCK BACKGROUND", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(DockBgOpacity.NONE to "None", DockBgOpacity.LOW to "Low",
                       DockBgOpacity.MEDIUM to "Medium", DockBgOpacity.HIGH to "High").forEachIndexed { i, (value, label) ->
                    val active = dockBgOpacity == value
                    val navSel = isNav(20 + i)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active || navSel) Amber.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
                            .border(1.dp, when { active -> AmberSoft; navSel -> Color.White.copy(alpha = 0.7f); else -> Color.White.copy(alpha = 0.12f) }, RoundedCornerShape(9.dp))
                            .clickable { onDockBgOpacityChange(value) }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            color = if (active || navSel) Amber else TextDim)
                    }
                }
            }
        }

        // ── Dock title size section — navIdx 27..29 ──────────────────────
        item {
            Spacer(Modifier.height(4.dp))
            Text("DOCK TITLE SIZE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(DockTitleSize.SMALL to "S", DockTitleSize.MEDIUM to "M", DockTitleSize.LARGE to "L").forEachIndexed { i, (value, label) ->
                    val active = dockTitleSize == value
                    val navSel = isNav(24 + i)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active || navSel) Amber.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
                            .border(1.dp, when { active -> AmberSoft; navSel -> Color.White.copy(alpha = 0.7f); else -> Color.White.copy(alpha = 0.12f) }, RoundedCornerShape(9.dp))
                            .clickable { onDockTitleSizeChange(value) }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = if (active || navSel) Amber else TextDim)
                    }
                }
            }
        }

        // ── Wallpaper section ────────────────────────────────────────────
        item {
            Spacer(Modifier.height(4.dp))
            Text("WALLPAPER", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
        }

        // Presets in rows of 4 — navIdx 27..27+N-1
        PRESET_WALLPAPERS.chunked(4).forEachIndexed { rowIdx, row ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    row.forEachIndexed { colIdx, preset ->
                        val flatIdx = rowIdx * 4 + colIdx
                        WallpaperTile(
                            isActive      = wallpaperState is WallpaperState.Preset && wallpaperState.id == preset.id,
                            label         = preset.name,
                            background    = Brush.linearGradient(preset.colors),
                            modifier      = Modifier.weight(1f).aspectRatio(1.6f),
                            isNavSelected = isNav(27 + flatIdx),
                            onClick       = { onWallpaperChange(WallpaperState.Preset(preset.id)) }
                        )
                    }
                    // Pad last row if not full
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        // Animated tile — navIdx 27+N
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                WallpaperTile(
                    isActive      = wallpaperState is WallpaperState.Animated,
                    label         = "Animated",
                    badge         = "LIVE",
                    background    = Brush.radialGradient(listOf(Color(0xFF4F0A60), Color(0xFF0A0A2A))),
                    modifier      = Modifier.weight(1f).aspectRatio(1.6f),
                    isNavSelected = isNav(27 + N),
                    onClick       = { onWallpaperChange(WallpaperState.Animated) }
                )
                Spacer(Modifier.weight(3f))
            }
        }

        // Custom wallpapers row
        item {
            Spacer(Modifier.height(4.dp))
            Text("CUSTOM WALLPAPERS", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
        }
        item {
            // Custom tiles navIdx 27+N+1..27+N+M, add button navIdx 27+N+M+1
            // Tile width matches the 4-column preset grid: (availableWidth - 3 gaps) / 4
            BoxWithConstraints {
                val tileW = (maxWidth - 21.dp) / 4
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                itemsIndexed(customWallpapers, key = { _, uri -> uri.toString() }) { i, uri ->
                    val isActive  = wallpaperState is WallpaperState.Custom && wallpaperState.uri == uri
                    val navSel    = isNav(27 + N + 1 + i)
                    val imgBorder = when {
                        navSel && isActive -> Amber
                        navSel            -> Color.White.copy(alpha = 0.7f)
                        isActive          -> Amber
                        else              -> Color.White.copy(alpha = 0.15f)
                    }
                    Box(modifier = Modifier.width(tileW).aspectRatio(1.6f)) {
                        AsyncImage(
                            model              = uri,
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp))
                                .border(2.dp, imgBorder, RoundedCornerShape(10.dp))
                                .clickable { onWallpaperChange(WallpaperState.Custom(uri)) }
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable { onRemoveWallpaper(uri) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✕", fontSize = 8.sp, color = Color.White)
                        }
                    }
                }
                item {
                    val navSel = isNav(27 + N + M + 1)
                    Box(
                        modifier = Modifier
                            .width(tileW)
                            .aspectRatio(1.6f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (navSel) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.06f))
                            .border(1.dp, if (navSel) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                            .clickable { imagePicker.launch(arrayOf("image/*")) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+", fontSize = 22.sp, color = if (navSel) TextPrimary else TextDim)
                    }
                }
            }
            } // BoxWithConstraints
        }
    }
}

// ── Emulators panel ──────────────────────────────────────────────────────────

@Composable
fun EmulatorsPanel(
    assignments: Map<String, String>,
    installedApps: List<InstalledApp>,
    onAssignmentChange: (String, String?) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    selectedItemIndex: Int = -1,
    onItemCountChange: (Int) -> Unit = {},
    activateTick: Int = 0,
    dropdownSystemId: String? = null,
    onDropdownSystemChange: (String?) -> Unit = {},
    onDropdownOpen: (Int) -> Unit = {},
    onDropdownClose: () -> Unit = {},
    dropdownSelectedIndex: Int = 0,
    dropdownActivateTick: Int = 0
) {
    val retroarchInstalled = remember(installedApps) {
        installedApps.any { it.packageName.startsWith("com.retroarch") }
    }
    val grouped = remember(installedApps, retroarchInstalled) {
        ALL_SYSTEMS
            .filter { sys ->
                retroarchInstalled &&
                sys.knownPackages.any { it.startsWith("com.retroarch") } &&
                sys.retroarchCores.any { RetroArchLauncher.isCoreInstalled(it) }
            }
            .groupBy { it.category }
    }

    // Flat list of navigable systems (no headers) and their LazyColumn indices.
    // lazyIndexMap accounts for any inline option rows inserted below the expanded system.
    val allSystems = remember(grouped) { grouped.values.flatten() }

    // Compute options for the currently-open dropdown (must be before lazyIndexMap)
    val expandedOptions = remember(dropdownSystemId, installedApps, allSystems) {
        val sys = if (dropdownSystemId != null) allSystems.firstOrNull { it.id == dropdownSystemId }
                  else null
        if (sys == null) return@remember emptyList<EmulatorOption>()
        buildList {
            sys.retroarchCores.filter { RetroArchLauncher.isCoreInstalled(it) }
                .forEach { add(EmulatorOption(it, RetroArchLauncher.assignmentFor(it))) }
        }
    }
    val lazyIndexMap = remember(grouped, dropdownSystemId, expandedOptions.size) {
        val map = mutableListOf<Int>()
        var lazyIdx = 0
        grouped.forEach { (_, systems) ->
            lazyIdx++ // header item
            systems.forEach { sys ->
                map += lazyIdx
                lazyIdx++
                if (sys.id == dropdownSystemId) lazyIdx += 1 + expandedOptions.size
            }
        }
        map
    }
    LaunchedEffect(allSystems.size) { onItemCountChange(allSystems.size) }
    LaunchedEffect(selectedItemIndex) {
        val lazyIdx = lazyIndexMap.getOrNull(selectedItemIndex) ?: return@LaunchedEffect
        listState.animateScrollToItem(lazyIdx)
    }
    LaunchedEffect(dropdownSelectedIndex, dropdownSystemId) {
        if (dropdownSystemId == null) return@LaunchedEffect
        val sysIdx     = allSystems.indexOfFirst { it.id == dropdownSystemId }
        val sysLazyIdx = lazyIndexMap.getOrNull(sysIdx) ?: return@LaunchedEffect
        listState.animateScrollToItem(sysLazyIdx + 1 + dropdownSelectedIndex)
    }

    // A-button: toggle dropdown for the focused row
    LaunchedEffect(activateTick) {
        if (activateTick == 0) return@LaunchedEffect
        val sys = allSystems.getOrNull(selectedItemIndex) ?: return@LaunchedEffect
        onDropdownSystemChange(if (dropdownSystemId == sys.id) null else sys.id)
    }

    LaunchedEffect(dropdownSystemId) {
        if (dropdownSystemId != null) onDropdownOpen(expandedOptions.size + 1) // +1 for "Not set"
        else onDropdownClose()
    }

    // A-button inside dropdown: apply the selected item and close
    val latestExpandedOptions by rememberUpdatedState(expandedOptions)
    val latestDropdownSysId   by rememberUpdatedState(dropdownSystemId)
    LaunchedEffect(dropdownActivateTick) {
        if (dropdownActivateTick == 0) return@LaunchedEffect
        val sysId = latestDropdownSysId ?: return@LaunchedEffect
        if (dropdownSelectedIndex == 0) {
            onAssignmentChange(sysId, null)
        } else {
            val opt = latestExpandedOptions.getOrNull(dropdownSelectedIndex - 1) ?: return@LaunchedEffect
            onAssignmentChange(sysId, opt.value)
        }
        onDropdownSystemChange(null)
    }

    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier) {
        // Refresh row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Tap ↺ to re-scan installed apps",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = TextFaint
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .clickable {
                        refreshing = true
                        onRefresh()
                        scope.launch {
                            delay(800.milliseconds)
                            refreshing = false
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (refreshing) "…" else "↺  Refresh",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (refreshing) TextFaint else Amber
                )
            }
        }

    LazyColumn(
        state          = listState,
        modifier       = Modifier.weight(1f).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 28.dp, top = 4.dp)
    ) {
        grouped.forEach { (category, systems) ->
            item(key = "header_$category") {
                Text(
                    text       = category.uppercase(),
                    fontSize   = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color      = TextFaint,
                    modifier   = Modifier.padding(top = 14.dp, bottom = 2.dp)
                )
            }
            systems.forEach { sys ->
                val navIdx     = allSystems.indexOf(sys)
                val isExpanded = dropdownSystemId == sys.id
                item(key = sys.id) {
                    EmulatorRow(
                        system           = sys,
                        assigned         = assignments[sys.id],
                        installedApps    = installedApps,
                        onAssign         = { pkg -> onAssignmentChange(sys.id, pkg) },
                        isSelected       = navIdx == selectedItemIndex,
                        expanded         = isExpanded,
                        onExpandedChange = { open -> onDropdownSystemChange(if (open) sys.id else null) }
                    )
                }
                if (isExpanded) {
                    item(key = "${sys.id}_notset") {
                        DropdownOptionRow(
                            label     = "Not set",
                            isSelected = dropdownSelectedIndex == 0,
                            isCurrent  = assignments[sys.id] == null,
                            onClick   = { onAssignmentChange(sys.id, null); onDropdownSystemChange(null) }
                        )
                    }
                    expandedOptions.forEachIndexed { i, opt ->
                        item(key = "${sys.id}_opt_$i") {
                            DropdownOptionRow(
                                label     = opt.label,
                                isSelected = dropdownSelectedIndex == i + 1,
                                isCurrent  = assignments[sys.id] == opt.value,
                                onClick   = { onAssignmentChange(sys.id, opt.value); onDropdownSystemChange(null) }
                            )
                        }
                    }
                }
            }
        }
    }
    } // end Column
}

/** A selectable option in the emulator dropdown. */
private data class EmulatorOption(val label: String, val value: String)

@Composable
fun EmulatorRow(
    system: RetroSystem,
    assigned: String?,
    installedApps: List<InstalledApp>,
    onAssign: (String?) -> Unit,
    isSelected: Boolean = false,
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {}
) {
    val assignedLabel = when {
        assigned == null         -> null
        assigned.contains("::") -> "${RetroArchLauncher.coreFromAssignment(assigned)} (RetroArch)"
        else                     -> installedApps.find { it.packageName == assigned }?.label ?: assigned
    }

    val rowShape   = RoundedCornerShape(14.dp)
    val badgeShape = RoundedCornerShape(9.dp)
    val highlight  = isSelected || expanded

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(if (highlight) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (highlight) Amber.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.09f), rowShape)
            .clickable { onExpandedChange(!expanded) }
            .padding(12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(badgeShape)
                .background(Brush.linearGradient(listOf(Color(system.colorStart.toInt()), Color(system.colorEnd.toInt())))),
            contentAlignment = Alignment.Center
        ) {
            Text(system.label, fontSize = 6.5.sp, color = TextPrimary.copy(alpha = 0.92f), fontFamily = FontFamily.Monospace)
        }

        Text(
            text       = system.fullName,
            modifier   = Modifier.weight(1f),
            fontWeight = FontWeight.SemiBold,
            fontSize   = 14.sp,
            color      = TextPrimary
        )

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(Color.White.copy(alpha = 0.07f))
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(9.dp))
                .padding(horizontal = 9.dp, vertical = 8.dp)
                .widthIn(min = 120.dp, max = 160.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text       = assignedLabel ?: "Not set",
                fontSize   = 11.sp,
                fontFamily = FontFamily.Monospace,
                color      = if (assigned != null) TextDim else Amber,
                fontStyle  = if (assigned == null) FontStyle.Italic else FontStyle.Normal,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f)
            )
            Text(if (expanded) " ▲" else " ▼", fontSize = 9.sp, color = TextFaint)
        }
    }
}

@Composable
private fun DropdownOptionRow(
    label: String,
    isSelected: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 58.dp)
            .clip(shape)
            .background(if (isSelected) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.03f))
            .then(if (isSelected) Modifier.border(1.dp, Amber.copy(alpha = 0.55f), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text   = if (isCurrent) "✓" else " ",
            fontSize = 11.sp,
            color  = Amber,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(16.dp)
        )
        Text(
            text     = label,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color    = if (isCurrent) Amber else TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
