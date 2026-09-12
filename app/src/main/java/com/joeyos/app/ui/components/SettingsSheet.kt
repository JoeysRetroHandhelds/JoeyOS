package com.joeyos.app.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.TextUnit
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
import com.joeyos.app.ui.controls.Control
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

/**
 * Settings, a full-screen page (JoeyPage): B / Back and Start close it, L1 / R1 switch tabs, and
 * focus goes back to the dock icon you left. Every control is a single focus target lit by its
 * real focus; the D-pad moves between them.
 */
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
    onCheckUpdates: () -> Unit = {},
    onShareCrashLog: () -> Unit = {},
    biosFolder: String = "",
    onBiosFolderChange: (String) -> Unit = {}
) {
    val tabs = listOf("Appearance", "Emulators", "Achievements", "Tools")
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabFocus = remember { List(tabs.size) { FocusRequester() } }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    // Each tab's list, hoisted so Down from the tab row can scroll it back to the top.
    val panelLists = remember { List(tabs.size) { LazyListState() } }
    val panelFirst = remember { FocusRequester() }

    /**
     * Down from the full-width tab row goes to the tab's first control. Left to itself, focus
     * search picks whatever sits nearest the tab's centre (found on device: 42, not 32).
     */
    fun enterPanel() {
        scope.launch {
            panelLists[selectedTab].scrollToItem(0)
            withFrameNanos { }
            val landed = runCatching { panelFirst.requestFocus() }.isSuccess
            if (!landed) focusManager.moveFocus(FocusDirection.Down)
        }
    }

    /** Switch tab and put focus on its tab button, since whatever was focused has just gone. */
    fun switchTab(to: Int) {
        selectedTab = to.coerceIn(0, tabs.lastIndex)
        scope.launch {
            withFrameNanos { }
            runCatching { tabFocus[selectedTab].requestFocus() }
        }
    }

    JoeyPage(
        onClose      = onDismiss,
        initialFocus = tabFocus[0],
        onControl    = { control ->
            when (control) {
                Control.Options  -> onDismiss()   // Start opened Settings, so Start closes it too
                Control.StepPrev -> switchTab(selectedTab - 1)
                Control.StepNext -> switchTab(selectedTab + 1)
                else -> {}
            }
            true
        }
    ) {
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
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text("JoeyOS", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Amber)
                    Text("L1/R1 tabs  •  B close", fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace, color = TextFaint)
                }

                // Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 12.dp)
                        .focusRow(tabFocus[selectedTab]),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tabs.forEachIndexed { i, title ->
                        val active = selectedTab == i
                        val (source, focused) = rememberFocusState()
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(tabFocus[i])
                                .onPreviewKeyEvent { e ->
                                    if (e.key == Key.DirectionDown) {
                                        if (e.type == KeyEventType.KeyDown) enterPanel()
                                        true
                                    } else false
                                }
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) Amber.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
                                .border(if (focused) FocusWidth else 1.dp,
                                    when {
                                        focused -> FocusColor
                                        active  -> AmberSoft
                                        else    -> Color.White.copy(0.14f)
                                    },
                                    RoundedCornerShape(10.dp))
                                .clickable(interactionSource = source, indication = null) { selectedTab = i }
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
                        listState                = panelLists[0],
                        firstFocus               = panelFirst
                    )
                    1 -> EmulatorsPanel(
                        assignments        = assignments,
                        installedApps      = installedApps,
                        onAssignmentChange = onAssignmentChange,
                        onRefresh          = onRefreshApps,
                        modifier           = Modifier.weight(1f),
                        listState          = panelLists[1],
                        firstFocus         = panelFirst
                    )
                    2 -> RetroAchievementsTab(
                        raRepo   = raRepo,
                        ibRepo   = ibRepo,
                        modifier = Modifier.weight(1f),
                        listState = panelLists[2],
                        firstFocus = panelFirst
                    )
                    3 -> ToolsPanel(
                        installedApps      = installedApps,
                        biosFolder         = biosFolder,
                        onBiosFolderChange = onBiosFolderChange,
                        modifier           = Modifier.weight(1f),
                        listState          = panelLists[3],
                        firstFocus         = panelFirst,
                        onCheckUpdates     = onCheckUpdates,
                        onShareLog         = onShareCrashLog
                    )
                }
            }
        }
    }
}

@Composable
fun WallpaperTile(
    focusModifier: Modifier = Modifier,
    isActive: Boolean,
    label: String,
    background: Brush,
    modifier: Modifier = Modifier,
    badge: String? = null,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val (source, focused) = rememberFocusState()
    Box(
        modifier = modifier
            .then(focusModifier)
            .clip(shape)
            .background(background)
            .then(when {
                focused  -> Modifier.border(3.dp, FocusColor, shape)
                isActive -> Modifier.border(1.dp, AmberSoft, shape)
                else     -> Modifier
            })
            .clickable(interactionSource = source, indication = null, onClick = onClick)
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
        if (isActive) ActiveTick(Modifier.align(Alignment.TopStart))
        Text(
            text     = label,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            fontSize = 10.5.sp,
            color    = Color.White,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** The "this is the current wallpaper" mark, kept apart from the focus ring. */
@Composable
private fun ActiveTick(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(6.dp)
            .size(18.dp)
            .clip(CircleShape)
            .background(Amber),
        contentAlignment = Alignment.Center
    ) {
        Text("✓", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
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
    firstFocus: FocusRequester? = null
) {
    // A on a custom wallpaper opens its options (use / remove). The ✕ inside the tile can't be
    // reached with the D-pad — it sits within the tile's bounds — so this is the controller way.
    var wallpaperOptions by remember { mutableStateOf<Uri?>(null) }
    wallpaperOptions?.let { uri ->
        JoeyPopup(title = "Custom wallpaper", onDismiss = { wallpaperOptions = null }, padded = false) {
            PopupRow("Use as wallpaper", {
                onWallpaperChange(WallpaperState.Custom(uri)); wallpaperOptions = null
            })
            PopupRow("Remove", {
                onRemoveWallpaper(uri); wallpaperOptions = null
            })
        }
    }

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

    // The list brings the focused control into view by itself as the D-pad walks it.
    LazyColumn(
        state          = listState,
        modifier       = modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
    ) {
        // ── Icon size ────────────────────────────────────────────────────
        item { SectionLabel("DOCK ICON SIZE") }
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
            val first = remember { FocusRequester() }
            Row(modifier = Modifier.fillMaxWidth().focusRow(first), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEachIndexed { i, size ->
                    OptionChip("$size", size == steps[currentStep], { onSizeChange(size) },
                        Modifier.weight(1f).then(
                            if (i == 0) Modifier.focusRequester(first).then(
                                if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                            else Modifier),
                        fontSize = 9.sp)
                }
            }
        }
        item {
            val first = remember { FocusRequester() }
            Row(modifier = Modifier.fillMaxWidth().focusRow(first), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                JoeyButton("−", { onSizeChange(steps[(currentStep - 1).coerceAtLeast(0)]) },
                    Modifier.weight(1f).focusRequester(first), fontSize = 22.sp)
                JoeyButton("+", { onSizeChange(steps[(currentStep + 1).coerceAtMost(steps.lastIndex)]) },
                    Modifier.weight(1f), fontSize = 22.sp)
            }
        }

        item {
            ChoiceRow("DOCK ORDER", listOf(
                DockSortOrder.RECENTLY_USED     to "Recent",
                DockSortOrder.ALPHABETICAL      to "A-Z",
                DockSortOrder.ALPHABETICAL_DESC to "Z-A"
            ), dockSortOrder, onSortOrderChange)
        }
        item { ChoiceRow("RECENT TITLE BADGE", listOf(true to "Show", false to "Hide"), showRecentBadge, onShowRecentBadgeChange) }
        item {
            ChoiceRow("CLOCK FORMAT", listOf(ClockFormat.H24 to "24h", ClockFormat.H12 to "12h", ClockFormat.HIDDEN to "Off"),
                clockFormat, onClockFormatChange)
        }
        item { ChoiceRow("RECENT LIST DEPTH", listOf(5 to "5", 10 to "10", 20 to "20"), recentDepth, onRecentDepthChange) }
        item {
            ChoiceRow("DOCK BACKGROUND", listOf(DockBgOpacity.NONE to "None", DockBgOpacity.LOW to "Low",
                DockBgOpacity.MEDIUM to "Medium", DockBgOpacity.HIGH to "High"), dockBgOpacity, onDockBgOpacityChange, fontSize = 9.sp)
        }
        item {
            ChoiceRow("DOCK TITLE SIZE", listOf(DockTitleSize.SMALL to "S", DockTitleSize.MEDIUM to "M", DockTitleSize.LARGE to "L"),
                dockTitleSize, onDockTitleSizeChange)
        }

        // ── Wallpaper ────────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(4.dp))
            SectionLabel("WALLPAPER")
        }
        PRESET_WALLPAPERS.chunked(4).forEach { row ->
            item {
                val first = remember { FocusRequester() }
                Row(modifier = Modifier.focusRow(first), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    row.forEachIndexed { i, preset ->
                        WallpaperTile(
                            focusModifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            isActive   = wallpaperState is WallpaperState.Preset && wallpaperState.id == preset.id,
                            label      = preset.name,
                            background = Brush.linearGradient(preset.colors),
                            modifier   = Modifier.weight(1f).aspectRatio(1.6f),
                            onClick    = { onWallpaperChange(WallpaperState.Preset(preset.id)) }
                        )
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                WallpaperTile(
                    isActive   = wallpaperState is WallpaperState.Animated,
                    label      = "Animated",
                    badge      = "LIVE",
                    background = Brush.radialGradient(listOf(Color(0xFF4F0A60), Color(0xFF0A0A2A))),
                    modifier   = Modifier.weight(1f).aspectRatio(1.6f),
                    onClick    = { onWallpaperChange(WallpaperState.Animated) }
                )
                Spacer(Modifier.weight(3f))
            }
        }

        // ── Custom wallpapers ────────────────────────────────────────────
        item {
            Spacer(Modifier.height(4.dp))
            SectionLabel("CUSTOM WALLPAPERS")
        }
        item {
            // Tile width matches the 4-column preset grid: (availableWidth - 3 gaps) / 4
            BoxWithConstraints {
                val tileW = (maxWidth - 21.dp) / 4
                val first = remember { FocusRequester() }
                LazyRow(modifier = Modifier.focusRow(first), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    itemsIndexed(customWallpapers, key = { _, uri -> uri.toString() }) { i, uri ->
                        val isActive = wallpaperState is WallpaperState.Custom && wallpaperState.uri == uri
                        val (source, focused) = rememberFocusState()
                        val imgBorder = when {
                            focused  -> FocusColor
                            isActive -> AmberSoft
                            else     -> Color.White.copy(alpha = 0.15f)
                        }
                        Box(modifier = Modifier.width(tileW).aspectRatio(1.6f)) {
                            AsyncImage(
                                model              = uri,
                                contentDescription = null,
                                contentScale       = ContentScale.Crop,
                                modifier           = (if (i == 0) Modifier.focusRequester(first) else Modifier)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(if (focused) 3.dp else 2.dp, imgBorder, RoundedCornerShape(10.dp))
                                    .clickable(interactionSource = source, indication = null) {
                                        wallpaperOptions = uri
                                    }
                            )
                            if (isActive) ActiveTick(Modifier.align(Alignment.TopStart))
                            // Quick remove by touch. (Not a D-pad stop: A on the tile has Remove.)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .focusProperties { canFocus = false }
                                    .clickable { onRemoveWallpaper(uri) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("✕", fontSize = 9.sp, color = Color.White)
                            }
                        }
                    }
                    item {
                        JoeyButton("+", { imagePicker.launch(arrayOf("image/*")) },
                            Modifier.width(tileW).aspectRatio(1.6f).then(
                                if (customWallpapers.isEmpty()) Modifier.focusRequester(first) else Modifier),
                            fontSize = 22.sp)
                    }
                }
            }
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
    firstFocus: FocusRequester? = null
) {
    val retroarchInstalled = remember(installedApps) {
        installedApps.any { it.packageName.startsWith("com.retroarch") }
    }
    // One list, A to Z by the console's full name: easier to find a system than by maker.
    val systems = remember(installedApps, retroarchInstalled) {
        ALL_SYSTEMS
            .filter { sys ->
                retroarchInstalled &&
                sys.knownPackages.any { it.startsWith("com.retroarch") } &&
                sys.retroarchCores.any { RetroArchLauncher.isCoreInstalled(it) }
            }
            .sortedBy { it.fullName.lowercase() }
    }

    // A on a system opens a picker popup (like Recently Played), focused on the current choice.
    var pickingFor by remember { mutableStateOf<RetroSystem?>(null) }
    pickingFor?.let { sys ->
        val options = remember(sys) {
            sys.retroarchCores.filter { RetroArchLauncher.isCoreInstalled(it) }
                .map { EmulatorOption(it, RetroArchLauncher.assignmentFor(it)) }
        }
        EmulatorPickerPopup(
            system    = sys,
            options   = options,
            current   = assignments[sys.id],
            onChoose  = { value -> onAssignmentChange(sys.id, value); pickingFor = null },
            onDismiss = { pickingFor = null }
        )
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
            Text("Re-scan installed apps", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
            JoeyButton(if (refreshing) "…" else "↺  Refresh", {
                refreshing = true
                onRefresh()
                scope.launch {
                    delay(800.milliseconds)
                    refreshing = false
                }
            }, modifier = if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier, fontSize = 11.sp)
        }

        LazyColumn(
            state          = listState,
            modifier       = Modifier.weight(1f).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 28.dp, top = 4.dp)
        ) {
            items(systems, key = { it.id }) { sys ->
                EmulatorRow(
                    system        = sys,
                    assigned      = assignments[sys.id],
                    installedApps = installedApps,
                    onClick       = { pickingFor = sys }
                )
            }
        }
    }
}

/** Pick the RetroArch core for one system. Opens on the current choice. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun EmulatorPickerPopup(
    system: RetroSystem,
    options: List<EmulatorOption>,
    current: String?,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val currentRow = remember { FocusRequester() }
    val currentIsSet = current != null && options.any { it.value == current }
    JoeyPopup(title = system.fullName, hint = "A set  •  B cancel", onDismiss = onDismiss,
        padded = false, initialFocus = currentRow) {
        LazyColumn(
            state = rememberLazyListState(
                initialFirstVisibleItemIndex = if (currentIsSet) options.indexOfFirst { it.value == current } + 1 else 0),
            modifier = Modifier.fillMaxWidth()
        ) {
            item(key = "notset") {
                PopupRow("Not set", isCurrent = !currentIsSet, onClick = { onChoose(null) },
                    modifier = if (!currentIsSet) Modifier.focusRequester(currentRow) else Modifier)
            }
            items(options, key = { it.value }) { opt ->
                PopupRow(opt.label, isCurrent = current == opt.value, onClick = { onChoose(opt.value) },
                    modifier = if (current == opt.value) Modifier.focusRequester(currentRow) else Modifier)
            }
        }
    }
}

/** A selectable option in the emulator dropdown. */
private data class EmulatorOption(val label: String, val value: String)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun EmulatorRow(
    system: RetroSystem,
    assigned: String?,
    installedApps: List<InstalledApp>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val assignedLabel = when {
        assigned == null         -> null
        assigned.contains("::") -> "${RetroArchLauncher.coreFromAssignment(assigned)} (RetroArch)"
        else                     -> installedApps.find { it.packageName == assigned }?.label ?: assigned
    }

    val badgeShape = RoundedCornerShape(9.dp)

    CardRow(onClick = onClick, modifier = modifier) { focused ->
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
            color      = if (focused) Amber else TextPrimary
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
            Text(" ▶", fontSize = 9.sp, color = TextFaint)
        }
    }
}
