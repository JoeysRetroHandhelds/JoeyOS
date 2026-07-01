package com.joeyos.app.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.joeyos.app.data.Aps3eLauncher
import com.joeyos.app.data.M64PlusFZLauncher
import com.joeyos.app.data.AzaharLauncher
import com.joeyos.app.ui.components.FAVORITE_PACKAGE
import com.joeyos.app.ui.components.RECENT_ALL_PACKAGE
import com.joeyos.app.data.EdenLauncher
import com.joeyos.app.data.Vita3KLauncher
import com.joeyos.app.data.CemuLauncher
import com.joeyos.app.data.DolphinLauncher
import com.joeyos.app.data.DuckStationLauncher
import com.joeyos.app.data.MelonDSLauncher
import com.joeyos.app.data.ARMSX2Launcher
import com.joeyos.app.data.NetherSX2Launcher
import com.joeyos.app.data.PpssppLauncher
import com.joeyos.app.data.DockCornerStyle
import com.joeyos.app.data.DockTitleSize
import com.joeyos.app.data.RecentGame
import com.joeyos.app.data.RecentGamesReader
import com.joeyos.app.data.RetroArchLauncher
import com.joeyos.app.ui.components.*
import com.joeyos.app.ui.components.buildDockEntries
import com.joeyos.app.ui.components.FavoritePickerPopup
import com.joeyos.app.ui.components.RecentGamesPopup
import com.joeyos.app.ui.viewmodel.ControllerEvent
import com.joeyos.app.ui.viewmodel.HomeViewModel
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val context        = LocalContext.current
    val wallpaperState    by viewModel.wallpaperState.collectAsStateWithLifecycle()
    val customWallpapers  by viewModel.customWallpapers.collectAsStateWithLifecycle()
    val assignments       by viewModel.assignments.collectAsStateWithLifecycle()
    val installedApps     by viewModel.installedApps.collectAsStateWithLifecycle()
    val dockIconSize      by viewModel.dockIconSize.collectAsStateWithLifecycle()
    val dockSortOrder     by viewModel.dockSortOrder.collectAsStateWithLifecycle()
    val favoriteGame      by viewModel.favoriteGame.collectAsStateWithLifecycle()
    val lastLaunched      by viewModel.lastLaunched.collectAsStateWithLifecycle()
    val selectedDockIndex by viewModel.selectedDockIndex.collectAsStateWithLifecycle()
    val showRecentBadge   by viewModel.showRecentBadge.collectAsStateWithLifecycle()
    val clockFormat       by viewModel.clockFormat.collectAsStateWithLifecycle()
    val recentDepth       by viewModel.recentDepth.collectAsStateWithLifecycle()
    val dockBgOpacity     by viewModel.dockBgOpacity.collectAsStateWithLifecycle()
    val dockTitleSize     by viewModel.dockTitleSize.collectAsStateWithLifecycle()

    var showSettings            by remember { mutableStateOf(false) }
    var settingsTab             by remember { mutableIntStateOf(0) }
    var settingsSelectedIndex   by remember { mutableIntStateOf(-1) }
    var settingsItemCount       by remember { mutableIntStateOf(0) }
    var showAppDrawer           by remember { mutableStateOf(false) }
    var appDrawerCols           by remember { mutableIntStateOf(4) }
    var selectedAppDrawer       by remember { mutableIntStateOf(0) }
    var appDrawerFilteredCount  by remember { mutableIntStateOf(0) }
    var appDrawerSelectedPkg    by remember { mutableStateOf<String?>(null) }
    var showRecentGames         by remember { mutableStateOf(false) }
    var recentGames             by remember { mutableStateOf<List<RecentGame>>(emptyList()) }
    var selectedRecent          by remember { mutableIntStateOf(0) }
    var showFavoritePicker      by remember { mutableStateOf(false) }
    var favoritePickerGames     by remember { mutableStateOf<List<RecentGame>>(emptyList()) }
    var selectedFavoritePicker  by remember { mutableIntStateOf(0) }
    var settingsActivateTick     by remember { mutableIntStateOf(0) }
    var settingsNavGrid          by remember { mutableStateOf<List<Int>?>(null) }
    var settingsDropdownSystemId by remember { mutableStateOf<String?>(null) }
    var dropdownItemCount        by remember { mutableIntStateOf(0) }
    var dropdownSelectedIndex    by remember { mutableIntStateOf(0) }
    var dropdownActivateTick     by remember { mutableIntStateOf(0) }

    val settingsTabCount = 3

    val currentShowSettings          by rememberUpdatedState(showSettings)
    val currentShowAppDrawer         by rememberUpdatedState(showAppDrawer)
    val currentSelectedAppDrawer     by rememberUpdatedState(selectedAppDrawer)
    val currentAppDrawerCount        by rememberUpdatedState(appDrawerFilteredCount)
    val currentAppDrawerPkg          by rememberUpdatedState(appDrawerSelectedPkg)
    val currentSettingsTab           by rememberUpdatedState(settingsTab)
    val currentShowRecent            by rememberUpdatedState(showRecentGames)
    val currentSelectedRecent        by rememberUpdatedState(selectedRecent)
    val currentRecentGames           by rememberUpdatedState(recentGames)
    val currentShowFavoritePicker    by rememberUpdatedState(showFavoritePicker)
    val currentSelectedFavPicker     by rememberUpdatedState(selectedFavoritePicker)
    val currentFavPickerGames        by rememberUpdatedState(favoritePickerGames)
    val currentDrawerCols            by rememberUpdatedState(appDrawerCols)
    val currentSettingsSelectedIndex by rememberUpdatedState(settingsSelectedIndex)
    val currentSettingsItemCount     by rememberUpdatedState(settingsItemCount)
    val currentSettingsNavGrid       by rememberUpdatedState(settingsNavGrid)
    val currentDropdownOpen          by rememberUpdatedState(settingsDropdownSystemId != null)
    val currentDropdownSelected      by rememberUpdatedState(dropdownSelectedIndex)
    val currentDropdownCount         by rememberUpdatedState(dropdownItemCount)

    val dockListState     = rememberLazyListState()
    val settingsListState = rememberLazyListState()
    val scope             = rememberCoroutineScope()
    val currentSettingsListState by rememberUpdatedState(settingsListState)

    val effectiveIconSize = if (dockIconSize > 0) dockIconSize else {
        val dpi = context.resources.displayMetrics.densityDpi
        ((72f * dpi / 420f).toInt()).coerceIn(32, 120)
    }


    LaunchedEffect(Unit) { viewModel.loadInstalledApps(context) }
    LaunchedEffect(settingsTab) {
        settingsSelectedIndex = -1
        settingsNavGrid = null
        settingsDropdownSystemId = null
        dropdownSelectedIndex = 0
        dropdownItemCount = 0
    }
    // Reset selection when the tab's item count grows due to async data loading,
    // so a previously-selected index doesn't silently point to a different action.
    LaunchedEffect(settingsItemCount) {
        if (settingsSelectedIndex >= settingsItemCount) settingsSelectedIndex = -1
    }

    // Mirror Dock.kt's entry list so the A-button handler targets the same tiles.
    val dockEntries = remember(installedApps, lastLaunched, dockSortOrder) {
        buildDockEntries(installedApps, lastLaunched, dockSortOrder)
    }

    // When a game launches (lastLaunched updates), snap the dock selection to that emulator's
    // new position so returning to the home screen keeps the highlight on what was just used.
    LaunchedEffect(lastLaunched) {
        if (lastLaunched.isEmpty()) return@LaunchedEffect
        val topSystemId = lastLaunched.entries.maxByOrNull { it.value }?.key ?: return@LaunchedEffect
        val sys = com.joeyos.app.data.ALL_SYSTEMS.firstOrNull { it.id == topSystemId } ?: return@LaunchedEffect
        // Resolve the package from dockEntries (already built) rather than scanning installedApps again.
        val idx = dockEntries.indexOfFirst { entry ->
            sys.knownPackages.any { entry.packageName.startsWith(it) }
        }
        if (idx < 0) return@LaunchedEffect
        viewModel.setSelectedDockIndex(idx)
        dockListState.animateScrollToItem(idx)
    }
    val currentDockEntries by rememberUpdatedState(dockEntries)
    val density = context.resources.displayMetrics.density

    // 2D grid helpers for Appearance panel navigation
    fun flatToRowCol(grid: List<Int>, idx: Int): Pair<Int, Int> {
        var rem = idx.coerceAtLeast(0)
        for ((r, count) in grid.withIndex()) {
            if (rem < count) return r to rem
            rem -= count
        }
        return grid.lastIndex to (grid.last() - 1).coerceAtLeast(0)
    }
    fun rowColToFlat(grid: List<Int>, row: Int, col: Int) = grid.take(row).sum() + col

    // Controller events. Routes contextually based on which overlay is open.
    LaunchedEffect(Unit) {
        viewModel.controllerEvents.collect { event ->
            suspend fun openRecentGames(packageName: String) {
                if (!RecentGamesReader.supportsRecentlyPlayed(packageName)) return
                Log.d("HomeScreen", "openRecentGames: called for pkg=$packageName")
                recentGames = emptyList(); selectedRecent = 0; showRecentGames = true
                val games = withContext(Dispatchers.IO) { RecentGamesReader.readForPackage(packageName, recentDepth) }
                Log.d("HomeScreen", "openRecentGames: got ${games.size} games")
                if (games.isNotEmpty()) recentGames = games else showRecentGames = false
            }
            suspend fun openAllRecentGames() {
                recentGames = emptyList(); selectedRecent = 0; showRecentGames = true
                val games = withContext(Dispatchers.IO) { RecentGamesReader.readAll(installedApps, recentDepth) }
                if (games.isNotEmpty()) recentGames = games else showRecentGames = false
            }
            suspend fun openFavoritePicker() {
                favoritePickerGames = emptyList(); selectedFavoritePicker = 0; showFavoritePicker = true
                val games = withContext(Dispatchers.IO) { RecentGamesReader.readAll(installedApps) }
                favoritePickerGames = games
            }

            fun scrollIfNeeded() {
                scope.launch {
                    val newIdx = viewModel.selectedDockIndex.value
                    val visible = dockListState.layoutInfo.visibleItemsInfo
                    val isVisible = visible.any { it.index == newIdx }
                    if (newIdx == 0 || !isVisible) dockListState.animateScrollToItem(newIdx.coerceAtLeast(0))
                }
            }
            fun navLeft() {
                when {
                    currentShowSettings -> settingsTab = (currentSettingsTab - 1).coerceAtLeast(0)
                    else -> { viewModel.shiftDockIndex(-1); scrollIfNeeded() }
                }
            }
            fun navRight() {
                when {
                    currentShowSettings -> settingsTab = (currentSettingsTab + 1).coerceAtMost(settingsTabCount - 1)
                    else -> { viewModel.shiftDockIndex(+1); scrollIfNeeded() }
                }
            }

            fun settingsNavUp() {
                val grid = currentSettingsNavGrid
                if (grid != null) {
                    val cur = currentSettingsSelectedIndex
                    if (cur < 0) { settingsSelectedIndex = 0; return }
                    val (row, col) = flatToRowCol(grid, cur)
                    val newRow = (row - 1).coerceAtLeast(0)
                    settingsSelectedIndex = rowColToFlat(grid, newRow, col.coerceAtMost(grid[newRow] - 1))
                } else if (currentSettingsItemCount > 0) {
                    settingsSelectedIndex = (currentSettingsSelectedIndex - 1).coerceAtLeast(0)
                } else {
                    scope.launch { currentSettingsListState.animateScrollBy(-200f) }
                }
            }
            fun settingsNavDown() {
                val grid = currentSettingsNavGrid
                if (grid != null) {
                    val cur = currentSettingsSelectedIndex
                    if (cur < 0) { settingsSelectedIndex = 0; return }
                    val (row, col) = flatToRowCol(grid, cur)
                    val newRow = (row + 1).coerceAtMost(grid.lastIndex)
                    settingsSelectedIndex = rowColToFlat(grid, newRow, col.coerceAtMost(grid[newRow] - 1))
                } else if (currentSettingsItemCount > 0) {
                    settingsSelectedIndex = (currentSettingsSelectedIndex + 1).coerceAtMost(currentSettingsItemCount - 1)
                } else {
                    scope.launch { currentSettingsListState.animateScrollBy(200f) }
                }
            }
            fun settingsNavLeft() {
                val grid = currentSettingsNavGrid
                val cur = currentSettingsSelectedIndex
                if (grid != null && cur >= 0) {
                    val (row, col) = flatToRowCol(grid, cur)
                    settingsSelectedIndex = rowColToFlat(grid, row, (col - 1).coerceAtLeast(0))
                } else navLeft()
            }
            fun settingsNavRight() {
                val grid = currentSettingsNavGrid
                val cur = currentSettingsSelectedIndex
                if (grid != null && cur >= 0) {
                    val (row, col) = flatToRowCol(grid, cur)
                    settingsSelectedIndex = rowColToFlat(grid, row, (col + 1).coerceAtMost(grid[row] - 1))
                } else navRight()
            }

            when (event) {
                ControllerEvent.DpadUp -> when {
                    currentShowFavoritePicker ->
                        selectedFavoritePicker = (currentSelectedFavPicker - 1).coerceAtLeast(0)
                    currentShowRecent ->
                        selectedRecent = (currentSelectedRecent - 1).coerceAtLeast(0)
                    currentShowAppDrawer ->
                        selectedAppDrawer = (currentSelectedAppDrawer - currentDrawerCols).coerceAtLeast(0)
                    currentShowSettings && currentDropdownOpen ->
                        dropdownSelectedIndex = (currentDropdownSelected - 1).coerceAtLeast(0)
                    currentShowSettings -> settingsNavUp()
                }
                ControllerEvent.DpadDown -> when {
                    currentShowFavoritePicker ->
                        selectedFavoritePicker = (currentSelectedFavPicker + 1).coerceAtMost(currentFavPickerGames.lastIndex.coerceAtLeast(0))
                    currentShowRecent ->
                        selectedRecent = (currentSelectedRecent + 1).coerceAtMost(currentRecentGames.lastIndex.coerceAtLeast(0))
                    currentShowAppDrawer ->
                        selectedAppDrawer = (currentSelectedAppDrawer + currentDrawerCols).coerceAtMost((currentAppDrawerCount - 1).coerceAtLeast(0))
                    currentShowSettings && currentDropdownOpen ->
                        dropdownSelectedIndex = (currentDropdownSelected + 1).coerceAtMost((currentDropdownCount - 1).coerceAtLeast(0))
                    currentShowSettings -> settingsNavDown()
                }
                // L1 always changes settings tab (or dock); DpadLeft does within-row nav in Appearance
                ControllerEvent.L1 -> when {
                    currentShowFavoritePicker ->
                        selectedFavoritePicker = (currentSelectedFavPicker - 1).coerceAtLeast(0)
                    currentShowRecent ->
                        selectedRecent = (currentSelectedRecent - 1).coerceAtLeast(0)
                    currentShowAppDrawer ->
                        selectedAppDrawer = (currentSelectedAppDrawer - 1).coerceAtLeast(0)
                    else -> navLeft()
                }
                ControllerEvent.DpadLeft -> when {
                    currentShowFavoritePicker ->
                        selectedFavoritePicker = (currentSelectedFavPicker - 1).coerceAtLeast(0)
                    currentShowRecent ->
                        selectedRecent = (currentSelectedRecent - 1).coerceAtLeast(0)
                    currentShowAppDrawer ->
                        selectedAppDrawer = (currentSelectedAppDrawer - 1).coerceAtLeast(0)
                    currentShowSettings && currentDropdownOpen -> { /* dropdown items are vertical only */ }
                    currentShowSettings -> settingsNavLeft()
                    else -> navLeft()
                }
                ControllerEvent.R1 -> when {
                    currentShowFavoritePicker ->
                        selectedFavoritePicker = (currentSelectedFavPicker + 1).coerceAtMost(currentFavPickerGames.lastIndex.coerceAtLeast(0))
                    currentShowRecent ->
                        selectedRecent = (currentSelectedRecent + 1).coerceAtMost(currentRecentGames.lastIndex.coerceAtLeast(0))
                    currentShowAppDrawer ->
                        selectedAppDrawer = (currentSelectedAppDrawer + 1).coerceAtMost((currentAppDrawerCount - 1).coerceAtLeast(0))
                    else -> navRight()
                }
                ControllerEvent.DpadRight -> when {
                    currentShowFavoritePicker ->
                        selectedFavoritePicker = (currentSelectedFavPicker + 1).coerceAtMost(currentFavPickerGames.lastIndex.coerceAtLeast(0))
                    currentShowRecent ->
                        selectedRecent = (currentSelectedRecent + 1).coerceAtMost(currentRecentGames.lastIndex.coerceAtLeast(0))
                    currentShowAppDrawer ->
                        selectedAppDrawer = (currentSelectedAppDrawer + 1).coerceAtMost((currentAppDrawerCount - 1).coerceAtLeast(0))
                    currentShowSettings && currentDropdownOpen -> { /* dropdown items are vertical only */ }
                    currentShowSettings -> settingsNavRight()
                    else -> navRight()
                }
                ControllerEvent.L2 -> scope.launch {
                    dockListState.animateScrollBy(-(effectiveIconSize * 5f * density))
                }
                ControllerEvent.R2 -> scope.launch {
                    dockListState.animateScrollBy(effectiveIconSize * 5f * density)
                }
                ControllerEvent.A -> {
                    Log.d("HomeScreen", "A pressed: showRecent=$currentShowRecent showFav=$currentShowFavoritePicker showDrawer=$currentShowAppDrawer showSettings=$currentShowSettings dropdown=$currentDropdownOpen")
                    when {
                    currentShowFavoritePicker -> {
                        val game = currentFavPickerGames.getOrNull(currentSelectedFavPicker)
                        if (game != null) { viewModel.setFavoriteGame(game); showFavoritePicker = false }
                    }
                    currentShowRecent -> {
                        val game = currentRecentGames.getOrNull(currentSelectedRecent)
                        if (game != null) { showRecentGames = false; launchRecentGame(context, game, assignments, viewModel) }
                    }
                    currentShowAppDrawer -> {
                        val pkg = currentAppDrawerPkg
                        if (pkg != null) { showAppDrawer = false; viewModel.launchApp(context, pkg) }
                    }
                    currentShowSettings && currentDropdownOpen -> dropdownActivateTick++
                    currentShowSettings -> settingsActivateTick++
                    else -> {
                        val idx     = selectedDockIndex
                        val entries = currentDockEntries
                        if (idx in entries.indices) {
                            when (val pkg = entries[idx].packageName) {
                                FAVORITE_PACKAGE -> {
                                    val fav = favoriteGame
                                    if (fav != null) launchRecentGame(context, fav, assignments, viewModel)
                                    else openFavoritePicker()
                                }
                                RECENT_ALL_PACKAGE -> {
                                    val top = withContext(Dispatchers.IO) { RecentGamesReader.readAll(installedApps) }.firstOrNull()
                                    if (top != null) launchRecentGame(context, top, assignments, viewModel)
                                }
                                else -> viewModel.launchApp(context, pkg)
                            }
                        }
                    }
                } // end when (A)
                } // end A -> { }
                ControllerEvent.X -> {
                    val idx     = selectedDockIndex
                    val entries = currentDockEntries
                    when {
                        idx !in entries.indices -> {
                            val top = withContext(Dispatchers.IO) { RecentGamesReader.readAll(installedApps) }.firstOrNull()
                            if (top != null) launchRecentGame(context, top, assignments, viewModel)
                        }
                        entries[idx].packageName == RECENT_ALL_PACKAGE -> {
                            val top = withContext(Dispatchers.IO) { RecentGamesReader.readAll(installedApps) }.firstOrNull()
                            if (top != null) launchRecentGame(context, top, assignments, viewModel)
                        }
                        entries[idx].packageName == FAVORITE_PACKAGE -> {
                            val fav = favoriteGame
                            if (fav != null) launchRecentGame(context, fav, assignments, viewModel)
                        }
                        else -> {
                            val pkg = entries[idx].packageName
                            val top = withContext(Dispatchers.IO) { RecentGamesReader.readForPackage(pkg) }.firstOrNull()
                            if (top != null) launchRecentGame(context, top, assignments, viewModel)
                        }
                    }
                }
                ControllerEvent.Y -> {
                    val idx     = selectedDockIndex
                    val entries = currentDockEntries
                    when {
                        idx !in entries.indices                         -> openAllRecentGames()
                        entries[idx].packageName == RECENT_ALL_PACKAGE -> openAllRecentGames()
                        entries[idx].packageName == FAVORITE_PACKAGE   -> openFavoritePicker()
                        else -> openRecentGames(entries[idx].packageName)
                    }
                }
                ControllerEvent.LongPressA -> { /* unused */ }
                ControllerEvent.B -> when {
                    currentShowFavoritePicker -> showFavoritePicker = false
                    currentShowRecent         -> showRecentGames = false
                    currentShowAppDrawer      -> showAppDrawer = false
                    currentShowSettings && currentDropdownOpen -> settingsDropdownSystemId = null
                    currentShowSettings       -> { showSettings = false; settingsTab = 0 }
                    else                      -> { showAppDrawer = true; selectedAppDrawer = 0 }
                }
                ControllerEvent.Start -> {
                    if (currentShowSettings) { showSettings = false; settingsTab = 0 }
                    else showSettings = true
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Wallpaper ──────────────────────────────────────────────────────
        WallpaperLayer(
            state    = wallpaperState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { showSettings = true })
                }
        )

        // ── Top bar ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.Top
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppDrawerButton { showAppDrawer = true; selectedAppDrawer = 0 }
                SettingsGearButton { showSettings = true }
            }
            if (clockFormat != com.joeyos.app.data.ClockFormat.HIDDEN) {
                Clock(use24h = clockFormat == com.joeyos.app.data.ClockFormat.H24)
            }
        }

        // ── Dock ──────────────────────────────────────────────────────────
        Dock(
            dockEntries         = dockEntries,
            onEmulatorClick     = { pkg ->
                Log.d("HomeScreen", "dock onClick: pkg=$pkg")
                when (pkg) {
                    FAVORITE_PACKAGE -> {
                        val fav = favoriteGame
                        if (fav != null) launchRecentGame(context, fav, assignments, viewModel)
                        else {
                            favoritePickerGames = emptyList(); selectedFavoritePicker = 0; showFavoritePicker = true
                            scope.launch {
                                val games = withContext(Dispatchers.IO) { RecentGamesReader.readAll(installedApps) }
                                favoritePickerGames = games
                            }
                        }
                    }
                    RECENT_ALL_PACKAGE -> {
                        scope.launch {
                            val top = withContext(Dispatchers.IO) { RecentGamesReader.readAll(installedApps) }.firstOrNull()
                            if (top != null) launchRecentGame(context, top, assignments, viewModel)
                        }
                    }
                    else -> viewModel.launchApp(context, pkg)
                }
            },
            modifier            = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 10.dp),
            favoriteTitle       = favoriteGame?.title,
            selectedIndex       = selectedDockIndex,
            onEmulatorLongClick = { pkg ->
                scope.launch {
                    when (pkg) {
                        FAVORITE_PACKAGE -> {
                            favoritePickerGames = emptyList(); selectedFavoritePicker = 0; showFavoritePicker = true
                            val games = withContext(Dispatchers.IO) { RecentGamesReader.readAll(installedApps) }
                            favoritePickerGames = games
                        }
                        RECENT_ALL_PACKAGE -> {
                            recentGames = emptyList(); selectedRecent = 0; showRecentGames = true
                            val games = withContext(Dispatchers.IO) { RecentGamesReader.readAll(installedApps, recentDepth) }
                            if (games.isNotEmpty()) recentGames = games else showRecentGames = false
                        }
                        else -> {
                            if (!RecentGamesReader.supportsRecentlyPlayed(pkg)) {
                                viewModel.launchApp(context, pkg)
                            } else {
                                recentGames = emptyList(); selectedRecent = 0; showRecentGames = true
                                val games = withContext(Dispatchers.IO) { RecentGamesReader.readForPackage(pkg, recentDepth) }
                                if (games.isNotEmpty()) recentGames = games else showRecentGames = false
                            }
                        }
                    }
                }
            },
            iconSizeDp          = effectiveIconSize,
            listState           = dockListState,
            showBadge           = showRecentBadge,
            cornerStyle         = DockCornerStyle.CIRCLE,
            bgOpacity           = dockBgOpacity,
            titleSize           = dockTitleSize
        )

        // ── Recent games overlay ──────────────────────────────────────────
        if (showRecentGames) {
            RecentGamesPopup(
                games         = recentGames,
                selectedIndex = selectedRecent,
                onLaunch      = { game -> showRecentGames = false; launchRecentGame(context, game, assignments, viewModel) },
                onDismiss     = { showRecentGames = false }
            )
        }

        // ── Favorite picker overlay ───────────────────────────────────────
        if (showFavoritePicker) {
            FavoritePickerPopup(
                games           = favoritePickerGames,
                currentFavorite = favoriteGame,
                selectedIndex   = selectedFavoritePicker,
                onSelect        = { game -> viewModel.setFavoriteGame(game) },
                onDismiss       = { showFavoritePicker = false }
            )
        }

        // ── App drawer overlay ────────────────────────────────────────────
        if (showAppDrawer) {
            AppDrawer(
                installedApps             = installedApps,
                onLaunch                  = { pkg -> showAppDrawer = false; viewModel.launchApp(context, pkg) },
                onDismiss                 = { showAppDrawer = false },
                selectedIndex             = selectedAppDrawer,
                onFilteredCountChange     = { appDrawerFilteredCount = it },
                onSelectedPackageChange   = { appDrawerSelectedPkg = it },
                onColumnCountChange       = { appDrawerCols = it }
            )
        }

        // Settings overlay — inside Box so it truly covers the full screen
        if (showSettings) {
            SettingsSheet(
                wallpaperState        = wallpaperState,
                customWallpapers      = customWallpapers,
                assignments           = assignments,
                installedApps         = installedApps,
                dockIconSize          = effectiveIconSize,
                dockSortOrder         = dockSortOrder,
                onWallpaperChange     = viewModel::setWallpaper,
                onAddWallpaper        = viewModel::addCustomWallpaper,
                onRemoveWallpaper     = viewModel::removeCustomWallpaper,
                onAssignmentChange    = viewModel::setAssignment,
                onDockIconSizeChange      = viewModel::setDockIconSize,
                onDockSortOrderChange     = viewModel::setDockSortOrder,
                showRecentBadge           = showRecentBadge,
                clockFormat               = clockFormat,
                recentDepth               = recentDepth,
                dockBgOpacity             = dockBgOpacity,
                dockTitleSize             = dockTitleSize,
                onShowRecentBadgeChange   = viewModel::setShowRecentBadge,
                onClockFormatChange       = viewModel::setClockFormat,
                onRecentDepthChange       = viewModel::setRecentDepth,
                onDockBgOpacityChange     = viewModel::setDockBgOpacity,
                onDockTitleSizeChange     = viewModel::setDockTitleSize,
                onRefreshApps             = { viewModel.loadInstalledApps(context) },
                onDismiss             = { showSettings = false; settingsTab = 0 },
                raRepo                       = viewModel.raRepo,
                ibRepo                       = viewModel.ibRepo,
                selectedTab                  = settingsTab,
                onTabChange                  = { settingsTab = it },
                listState                    = settingsListState,
                settingsSelectedIndex        = settingsSelectedIndex,
                onSettingsItemCountChange    = { settingsItemCount = it },
                onSettingsNavGridChange      = { settingsNavGrid = it },
                activateTick                 = settingsActivateTick,
                dropdownSystemId             = settingsDropdownSystemId,
                onDropdownSystemChange       = { settingsDropdownSystemId = it },
                onDropdownOpen               = { dropdownItemCount = it; dropdownSelectedIndex = 0 },
                onDropdownClose              = { dropdownItemCount = 0 },
                dropdownSelectedIndex        = dropdownSelectedIndex,
                dropdownActivateTick         = dropdownActivateTick
            )
        }
    }

}

// ── Clock ─────────────────────────────────────────────────────────────────────

@Composable
fun Clock(use24h: Boolean = true) {
    val timeFmt = remember(use24h) { SimpleDateFormat(if (use24h) "HH:mm" else "h:mm a", Locale.getDefault()) }
    var time by remember(use24h) { mutableStateOf(timeFmt.format(Date())) }
    var date by remember { mutableStateOf(formattedDate()) }
    LaunchedEffect(use24h) {
        while (true) {
            delay(10.seconds)
            time = timeFmt.format(Date())
            date = formattedDate()
        }
    }
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val timeSp = (screenH * 0.085f).coerceIn(18f, 36f).sp
    val dateSp = (screenH * 0.028f).coerceIn(8f, 13f).sp
    Column(horizontalAlignment = Alignment.End) {
        Text(time, fontSize = timeSp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(date, fontSize = dateSp, color = Color.White.copy(alpha = 0.85f), fontFamily = FontFamily.Monospace)
    }
}

// ── App drawer button ─────────────────────────────────────────────────────────

@Composable
fun AppDrawerButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Surface(modifier = Modifier.fillMaxSize(), shape = CircleShape,
            color = Color.White.copy(alpha = 0.14f), tonalElevation = 0.dp) {}
        Text("⊞", fontSize = 16.sp, color = Color.White)
    }
}

// ── Settings gear button ──────────────────────────────────────────────────────

@Composable
fun SettingsGearButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Surface(modifier = Modifier.fillMaxSize(), shape = CircleShape,
            color = Color.White.copy(alpha = 0.14f), tonalElevation = 0.dp) {}
        Text("⚙", fontSize = 16.sp, color = Color.White)
    }
}

private fun formattedDate(): String = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date())

fun launchRecentGame(
    context: android.content.Context,
    game: RecentGame,
    assignments: Map<String, String>,
    viewModel: HomeViewModel
) {
    Log.d("launchRecentGame", "title=${game.title} pkg=${game.emulatorPackage} path=${game.path}")
    val launched = when {
        game.emulatorPackage.startsWith("com.retroarch") ->
            RetroArchLauncher.launch(context, game, assignments)
        game.emulatorPackage.startsWith("me.magnum.melonds") ||
        game.emulatorPackage.startsWith("me.magnum.melondualds") ->
            MelonDSLauncher.launch(context, game)
        game.emulatorPackage.startsWith("org.ppsspp") ->
            PpssppLauncher.launch(context, game)
        game.emulatorPackage.startsWith("xyz.aethersx2") ||
        game.emulatorPackage.startsWith("net.nicholaswilde.nethersx2") ->
            NetherSX2Launcher.launch(context, game)
        game.emulatorPackage.startsWith("com.armsx2") ->
            ARMSX2Launcher.launch(context, game)
        game.emulatorPackage.startsWith("org.dolphinemu") ->
            DolphinLauncher.launch(context, game)
        game.emulatorPackage.startsWith("org.azahar_emu") ->
            AzaharLauncher.launch(context, game)
        game.emulatorPackage.startsWith("info.cemu") ->
            CemuLauncher.launch(context, game)
        game.emulatorPackage.startsWith("dev.eden") ->
            EdenLauncher.launch(context, game)
        game.emulatorPackage.startsWith("org.vita3k") ->
            Vita3KLauncher.launch(context, game)
        game.emulatorPackage.startsWith("com.github.stenzek.duckstation") ||
        game.emulatorPackage.startsWith("com.duckstation") ->
            DuckStationLauncher.launch(context, game)
        game.emulatorPackage.startsWith("aenu.aps3e") ->
            Aps3eLauncher.launch(context, game)
        game.emulatorPackage.startsWith("org.mupen64plusae") ->
            M64PlusFZLauncher.launch(context, game)
        else -> false
    }
    if (!launched) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.fromFile(File(game.path))
            setPackage(game.emulatorPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { context.startActivity(intent) }
        catch (_: Exception) { viewModel.launchApp(context, game.emulatorPackage); return }
    }
    // Record the launch so dock sort-by-recent updates regardless of which path ran.
    viewModel.recordLaunchForPackage(game.emulatorPackage)
}
