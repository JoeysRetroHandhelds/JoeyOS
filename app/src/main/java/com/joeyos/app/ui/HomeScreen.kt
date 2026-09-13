package com.joeyos.app.ui

import com.joeyos.app.R
import com.joeyos.app.ui.theme.JoeyFont
import com.joeyos.app.data.startGame
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import com.joeyos.app.ui.controls.Control
import com.joeyos.app.ui.controls.ControlBus
import com.joeyos.app.AppLog
import com.joeyos.app.data.AppUpdates
import com.joeyos.app.data.Aps3eLauncher
import com.joeyos.app.data.ARMSX3Launcher
import com.joeyos.app.data.FlycastLauncher
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
import com.joeyos.app.ui.viewmodel.HomeViewModel
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    val showRecentBadge   by viewModel.showRecentBadge.collectAsStateWithLifecycle()
    val clockFormat       by viewModel.clockFormat.collectAsStateWithLifecycle()
    val recentDepth       by viewModel.recentDepth.collectAsStateWithLifecycle()
    val dockBgOpacity     by viewModel.dockBgOpacity.collectAsStateWithLifecycle()
    val dockTitleSize     by viewModel.dockTitleSize.collectAsStateWithLifecycle()
    val recentGamesVersion by viewModel.recentGamesVersion.collectAsStateWithLifecycle()
    val dockPinned        by viewModel.dockPinned.collectAsStateWithLifecycle()
    val dockHidden        by viewModel.dockHidden.collectAsStateWithLifecycle()
    val biosFolder        by viewModel.biosFolder.collectAsStateWithLifecycle()

    var showSettings            by remember { mutableStateOf(false) }
    var showAppDrawer           by remember { mutableStateOf(false) }
    var showRecentGames         by remember { mutableStateOf(false) }
    var recentGames             by remember { mutableStateOf<List<RecentGame>>(emptyList()) }
    var showFavoritePicker      by remember { mutableStateOf(false) }
    var favoritePickerGames     by remember { mutableStateOf<List<RecentGame>?>(null) }

    // ── In-app updater ────────────────────────────────────────────────────
    var update                   by remember { mutableStateOf<AppUpdates.Release?>(null) }
    var updateDownloading        by remember { mutableStateOf(false) }
    var updateProgress           by remember { mutableFloatStateOf(0f) }
    // APK downloaded while "Install unknown apps" was off — installed on the next resume once granted.
    var pendingInstall           by remember { mutableStateOf<java.io.File?>(null) }

    // ── Dock focus (the new input layer) ──────────────────────────────────
    // The focused icon is tracked by package, never by position, so a re-sort can't strand it.
    var focusedDockPkg           by remember { mutableStateOf<String?>(null) }
    val dockFocusRequesters      = remember { mutableMapOf<String, FocusRequester>() }

    val dockListState     = rememberLazyListState()
    val scope             = rememberCoroutineScope()

    val effectiveIconSize = if (dockIconSize > 0) dockIconSize else {
        val dpi = context.resources.displayMetrics.densityDpi
        ((72f * dpi / 420f).toInt()).coerceIn(32, 120)
    }


    // The app list is loaded by MainActivity (once at start, then on package changes).
    // Mirror Dock.kt's entry list so the A-button handler targets the same tiles.
    val dockEntries = remember(installedApps, lastLaunched, dockSortOrder, dockPinned, dockHidden) {
        buildDockEntries(installedApps, lastLaunched, dockSortOrder, dockPinned, dockHidden)
    }

    val currentDockEntries by rememberUpdatedState(dockEntries)


    // Where focus starts: the emulator you last played, else the first emulator, else Favorite.
    fun defaultDockPkg(): String? {
        val entries = currentDockEntries
        val topSystemId = lastLaunched.entries.maxByOrNull { it.value }?.key
        val sys = com.joeyos.app.data.ALL_SYSTEMS.firstOrNull { it.id == topSystemId }
        return entries.firstOrNull { e -> sys?.knownPackages?.any { e.packageName.startsWith(it) } == true }?.packageName
            ?: entries.getOrNull(2)?.packageName
            ?: entries.firstOrNull()?.packageName
    }

    val inputModeManager = LocalInputModeManager.current
    /** Moves focus to a dock icon by identity, scrolling it into the list first if needed. */
    suspend fun focusDock(pkg: String?) {
        val entries = currentDockEntries
        val idx = entries.indexOfFirst { it.packageName == pkg }
        if (idx < 0) return
        if (dockListState.layoutInfo.visibleItemsInfo.none { it.index == idx }) dockListState.scrollToItem(idx)
        withFrameNanos { }
        inputModeManager.requestInputMode(InputMode.Keyboard)
        runCatching { dockFocusRequesters[entries[idx].packageName]?.requestFocus() }
    }

    // Settings and the App Drawer are pages drawn over the home screen; while one is open the
    // dock is kept out of focus, and focus comes back to the icon you left when it closes.
    val pageOpen = showSettings || showAppDrawer

    // Always keep something focused on the home screen, so a press always has a target: on start
    // and whenever a page closes. (Popups are real Dialogs, which hand focus back by themselves.)
    LaunchedEffect(pageOpen, dockEntries.isNotEmpty()) {
        if (pageOpen || dockEntries.isEmpty()) return@LaunchedEffect
        val keep = focusedDockPkg?.takeIf { p -> dockEntries.any { it.packageName == p } }
        focusDock(keep ?: defaultDockPkg())
    }
    // Focus follows the icon when the dock re-sorts after a launch; keep that icon on screen.
    LaunchedEffect(dockEntries) {
        val idx = dockEntries.indexOfFirst { it.packageName == focusedDockPkg }
        if (idx >= 0 && dockListState.layoutInfo.visibleItemsInfo.none { it.index == idx }) {
            dockListState.animateScrollToItem(idx)
        }
    }

    fun startUpdate(release: AppUpdates.Release) {
        if (updateDownloading) return
        scope.launch {
            updateDownloading = true; updateProgress = 0f
            val apk = AppUpdates.download(context, release) { updateProgress = it }
            updateDownloading = false
            if (apk == null) {
                Toast.makeText(context, "Update download failed", Toast.LENGTH_LONG).show()
                return@launch
            }
            update = null
            if (AppUpdates.canInstall(context)) AppUpdates.install(context, apk)
            else {
                pendingInstall = apk
                Toast.makeText(context, "Allow JoeyOS to install apps, then come back", Toast.LENGTH_LONG).show()
                AppUpdates.requestInstallPermission(context)
            }
        }
    }
    fun checkForUpdates(manual: Boolean) {
        scope.launch {
            if (manual) Toast.makeText(context, "Checking for updates…", Toast.LENGTH_SHORT).show()
            val release = AppUpdates.newerRelease(context)
            when {
                release == null -> if (manual) Toast.makeText(context, "You're on the latest version", Toast.LENGTH_SHORT).show()
                // Chose Later for this one: only a manual check offers it again.
                !manual && release.versionName == AppUpdates.skippedVersion(context) ->
                    AppLog.i("Update", "v${release.versionName} available, skipped (chose Later)")
                else -> update = release
            }
        }
    }
    fun shareCrashLog() {
        val log = AppLog.readRecent(context)
        if (log == null) {
            Toast.makeText(context, "Nothing logged yet", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "JoeyOS log")
            putExtra(Intent.EXTRA_TEXT, log)
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(context, "No app to share with — the log is at /sdcard/JoeyOS/joeyos.log", Toast.LENGTH_LONG).show()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            pendingInstall?.let { apk ->
                if (AppUpdates.canInstall(context)) { pendingInstall = null; AppUpdates.install(context, apk) }
            }
            if (update == null && !updateDownloading && AppUpdates.autoCheckDue(context)) checkForUpdates(manual = false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── Dock actions ──────────────────────────────────────────────────────
    suspend fun openRecentGames(packageName: String) {
        if (!RecentGamesReader.supportsRecentlyPlayed(packageName)) return
        recentGames = emptyList(); showRecentGames = true
        val games = withContext(Dispatchers.IO) { RecentGamesReader.readForPackage(packageName, recentDepth) }
        if (games.isNotEmpty()) recentGames = games else showRecentGames = false
    }
    suspend fun openAllRecentGames() {
        recentGames = emptyList(); showRecentGames = true
        val games = withContext(Dispatchers.IO) { RecentGamesReader.readAll(installedApps, recentDepth) }
        if (games.isNotEmpty()) recentGames = games else showRecentGames = false
    }
    suspend fun openFavoritePicker() {
        favoritePickerGames = null; showFavoritePicker = true
        val games = withContext(Dispatchers.IO) { RecentGamesReader.readAll(installedApps) }
        favoritePickerGames = games
    }
    /** Y: Recently Played for the focused emulator, all of them on Recent, the picker on Favorite. */
    suspend fun openDockSecondary(pkg: String?) = when (pkg) {
        null, RECENT_ALL_PACKAGE -> openAllRecentGames()
        FAVORITE_PACKAGE         -> openFavoritePicker()
        else                     -> openRecentGames(pkg)
    }
    /** X: launch the focused emulator's most recent game (the favourite on Favorite). */
    suspend fun quickLaunch(pkg: String?) {
        val game = when (pkg) {
            FAVORITE_PACKAGE -> favoriteGame
            null, RECENT_ALL_PACKAGE -> withContext(Dispatchers.IO) { RecentGamesReader.readAll(installedApps) }.firstOrNull()
            else -> withContext(Dispatchers.IO) { RecentGamesReader.readForPackage(pkg) }.firstOrNull()
        }
        if (game != null) launchRecentGame(context, game, assignments, viewModel)
    }
    /** L1/R1 step one icon, L2/R2 a page of five, by identity. */
    fun stepDock(delta: Int) {
        val entries = currentDockEntries
        if (entries.isEmpty()) return
        val cur = entries.indexOfFirst { it.packageName == focusedDockPkg }.coerceAtLeast(0)
        val target = entries[(cur + delta).coerceIn(0, entries.lastIndex)].packageName
        scope.launch { focusDock(target) }
    }

    // The one owner of the app's own buttons while the home screen is in charge.
    DisposableEffect(Unit) {
        ControlBus.setHandler { control ->
            when (control) {
                Control.Options     -> showSettings = true
                Control.QuickLaunch -> { scope.launch { quickLaunch(focusedDockPkg) } }
                Control.Recent      -> { scope.launch { openDockSecondary(focusedDockPkg) } }
                Control.StepPrev    -> stepDock(-1)
                Control.StepNext    -> stepDock(+1)
                Control.PagePrev    -> stepDock(-5)
                Control.PageNext    -> stepDock(+5)
            }
            true
        }
        onDispose { ControlBus.setHandler(null) }
    }

    // B / Back on the home screen opens the App Drawer. A deliberate exception to the TV guidance
    // (Back does nothing at home), kept because it's the familiar handheld shortcut — Joey's call,
    // 2026-09-11. Pages and popups take Back themselves first.
    BackHandler(enabled = !pageOpen) { showAppDrawer = true }

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
                CircleIconButton(R.drawable.ic_apps) { showAppDrawer = true }
                CircleIconButton(R.drawable.ic_settings) { showSettings = true }
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
                        if (fav != null) scope.launch { launchRecentGame(context, fav, assignments, viewModel) }
                        else {
                            favoritePickerGames = null; showFavoritePicker = true
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
            focusedPackage      = focusedDockPkg,
            onFocusedChange     = { focusedDockPkg = it },
            focusRequesters     = dockFocusRequesters,
            focusEnabled        = { !showSettings && !showAppDrawer },
            onEmulatorLongClick = { pkg ->
                scope.launch {
                    when (pkg) {
                        FAVORITE_PACKAGE -> {
                            favoritePickerGames = null; showFavoritePicker = true
                            val games = withContext(Dispatchers.IO) { RecentGamesReader.readAll(installedApps) }
                            favoritePickerGames = games
                        }
                        RECENT_ALL_PACKAGE -> {
                            recentGames = emptyList(); showRecentGames = true
                            val games = withContext(Dispatchers.IO) { RecentGamesReader.readAll(installedApps, recentDepth) }
                            if (games.isNotEmpty()) recentGames = games else showRecentGames = false
                        }
                        else -> {
                            if (!RecentGamesReader.supportsRecentlyPlayed(pkg)) {
                                viewModel.launchApp(context, pkg)
                            } else {
                                recentGames = emptyList(); showRecentGames = true
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
            titleSize           = dockTitleSize,
            recentGamesVersion  = recentGamesVersion
        )

        // ── Recent games overlay ──────────────────────────────────────────
        if (showRecentGames) {
            RecentGamesPopup(
                games         = recentGames,
                onLaunch      = { game -> showRecentGames = false; scope.launch { launchRecentGame(context, game, assignments, viewModel) } },
                onDismiss     = { showRecentGames = false }
            )
        }

        // ── Favorite picker overlay ───────────────────────────────────────
        if (showFavoritePicker) {
            FavoritePickerPopup(
                games           = favoritePickerGames,
                currentFavorite = favoriteGame,
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
                isInDock                  = { pkg -> dockEntries.any { it.packageName == pkg } },
                onSetInDock               = viewModel::setInDock
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
                onDismiss                 = { showSettings = false },
                raRepo                    = viewModel.raRepo,
                onCheckUpdates               = { checkForUpdates(manual = true) },
                onShareCrashLog              = ::shareCrashLog,
                biosFolder                   = biosFolder,
                onBiosFolderChange           = viewModel::setBiosFolder
            )
        }

        update?.let { release ->
            UpdatePrompt(
                release          = release,
                installedVersion = AppUpdates.installedVersion(context),
                downloading      = updateDownloading,
                progress         = updateProgress,
                onUpdate         = { startUpdate(release) },
                onLater          = {
                    AppLog.i("Update", "Later: not offering v${release.versionName} again")
                    AppUpdates.skipVersion(context, release.versionName)
                    update = null
                }
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
        Text(date, fontSize = dateSp, color = Color.White.copy(alpha = 0.85f), fontFamily = JoeyFont)
    }
}

// ── Top-bar round buttons (app drawer, settings) ─────────────────────────────

/** A round, see-through button with a white icon: the app drawer and settings in the top bar. */
@Composable
fun CircleIconButton(@androidx.annotation.DrawableRes icon: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Surface(modifier = Modifier.fillMaxSize(), shape = CircleShape,
            color = Color.White.copy(alpha = 0.14f), tonalElevation = 0.dp) {}
        com.joeyos.app.ui.components.JoeyIcon(icon, Color.White, 20.dp)
    }
}

private fun formattedDate(): String = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date())

suspend fun launchRecentGame(
    context: android.content.Context,
    game: RecentGame,
    assignments: Map<String, String>,
    viewModel: HomeViewModel
) {
    AppLog.i("Launch", "'${game.title}' with ${game.emulatorPackage}" +
        if (game.path.isNotBlank()) " from ${game.path}" else "")
    // Most launchers resolve the ROM by scanning storage directories (RomFinder.findRomByTitle)
    // on every launch, not just as a fallback — keep that disk I/O off the main thread.
    // The second screen names the game straight away, before RetroAchievements has seen it.
    com.joeyos.app.data.SecondScreenState.willLaunch(game.title, game.path)
    val launched = withContext(Dispatchers.IO) {
        when {
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
            game.emulatorPackage.startsWith("org.dolphinemu") ||
            game.emulatorPackage.startsWith("com.joeyos.dolphinemu") ->
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
            game.emulatorPackage.startsWith("com.armsx3") ->
                ARMSX3Launcher.launch(context, game)
            game.emulatorPackage.startsWith("com.flycast.emulator") ->
                FlycastLauncher.launch(context, game)
            game.emulatorPackage.startsWith("org.mupen64plusae") ->
                M64PlusFZLauncher.launch(context, game)
            else -> false
        }
    }
    if (!launched) {
        // Hand the game file to the emulator the way Android allows (a content:// link it's granted
        // to read; a file:// one is refused outright). A save file with no game found for it is
        // no use to open, so then it's just the emulator.
        val rom = withContext(Dispatchers.IO) {
            com.joeyos.app.data.RomFinder.resolveRomFromSave(game.path)
                ?.takeIf { it.startsWith("content://") || File(it).isFile }
        }
        if (rom == null) {
            AppLog.w("Launch", "'${game.title}': ${game.emulatorPackage}'s launcher couldn't start it and no game file was found; opening ${game.emulatorPackage}")
            viewModel.launchApp(context, game.emulatorPackage); return
        }
        AppLog.w("Launch", "'${game.title}': ${game.emulatorPackage}'s launcher couldn't start it, opening $rom")
        com.joeyos.app.data.SecondScreenState.willLaunch(game.title, rom)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = com.joeyos.app.data.RomFinder.pathToGrantableUri(context, rom)
            setPackage(game.emulatorPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { context.startGame(intent) }
        catch (e: Exception) {
            AppLog.w("Launch", "'${game.title}': opening the file failed too, opening ${game.emulatorPackage} instead", e)
            viewModel.launchApp(context, game.emulatorPackage); return
        }
    }
    // Record the launch so dock sort-by-recent updates regardless of which path ran.
    viewModel.recordLaunchForPackage(game.emulatorPackage)
}
