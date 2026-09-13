package com.joeyos.app.ui.viewmodel

import com.joeyos.app.data.startApp
import com.joeyos.app.data.startGame
import android.content.Context
import com.joeyos.app.AppLog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.joeyos.app.data.ALL_SYSTEMS
import com.joeyos.app.data.GameDatabase
import com.joeyos.app.data.RecentGamesReader
import com.joeyos.app.data.ClockFormat
import com.joeyos.app.data.DockBgOpacity
import com.joeyos.app.data.DockSortOrder
import com.joeyos.app.data.DockTitleSize
import com.joeyos.app.data.RecentGame
import com.joeyos.app.data.InstalledApp
import com.joeyos.app.data.PreferencesRepository
import com.joeyos.app.data.RetroAchievementsRepository
import com.joeyos.app.data.RetroArchLauncher
import com.joeyos.app.data.WallpaperState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class HomeViewModel(
    private val repo: PreferencesRepository,
    val raRepo: RetroAchievementsRepository
) : ViewModel() {

    val wallpaperState: StateFlow<WallpaperState> = repo.wallpaperState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WallpaperState.Preset("sunset"))

    fun setWallpaper(state: WallpaperState) {
        viewModelScope.launch { repo.setWallpaper(state) }
    }

    val customWallpapers: StateFlow<List<Uri>> = repo.customWallpapers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addCustomWallpaper(uri: Uri) {
        viewModelScope.launch { repo.addCustomWallpaper(uri); repo.setWallpaper(WallpaperState.Custom(uri)) }
    }

    fun removeCustomWallpaper(uri: Uri) {
        if (uri.scheme == "file") runCatching { java.io.File(uri.path!!).delete() }
        viewModelScope.launch { repo.removeCustomWallpaper(uri) }
    }

    val assignments: StateFlow<Map<String, String>> = repo.allAssignments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val dockIconSize: StateFlow<Int> = repo.dockIconSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val dockSortOrder: StateFlow<DockSortOrder> = repo.dockSortOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DockSortOrder.RECENTLY_USED)

    fun setDockIconSize(size: Int) {
        viewModelScope.launch { repo.setDockIconSize(size.coerceIn(28, 120)) }
    }

    fun setDockSortOrder(order: DockSortOrder) {
        viewModelScope.launch { repo.setDockSortOrder(order) }
    }

    val showRecentBadge: StateFlow<Boolean> = repo.showRecentBadge
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val clockFormat: StateFlow<ClockFormat> = repo.clockFormat
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClockFormat.HIDDEN)

    val recentDepth: StateFlow<Int> = repo.recentDepth
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 20)

    val dockBgOpacity: StateFlow<DockBgOpacity> = repo.dockBgOpacity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DockBgOpacity.NONE)

    val dockTitleSize: StateFlow<DockTitleSize> = repo.dockTitleSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DockTitleSize.MEDIUM)

    fun setShowRecentBadge(show: Boolean)       { viewModelScope.launch { repo.setShowRecentBadge(show) } }
    fun setClockFormat(fmt: ClockFormat)        { viewModelScope.launch { repo.setClockFormat(fmt) } }
    fun setRecentDepth(d: Int)                  { viewModelScope.launch { repo.setRecentDepth(d) } }
    fun setDockBgOpacity(o: DockBgOpacity)      { viewModelScope.launch { repo.setDockBgOpacity(o) } }
    fun setDockTitleSize(s: DockTitleSize)      { viewModelScope.launch { repo.setDockTitleSize(s) } }

    val favoriteGame: StateFlow<RecentGame?> = repo.favoriteGame
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setFavoriteGame(game: RecentGame?) {
        viewModelScope.launch { repo.setFavoriteGame(game) }
    }

    val biosFolder: StateFlow<String> = repo.biosFolder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    fun setBiosFolder(path: String) {
        AppLog.i("Settings", if (path.isBlank()) "BIOS folder: back to automatic" else "BIOS folder set to $path")
        viewModelScope.launch { repo.setBiosFolder(path) }
    }

    val dockPinned: StateFlow<Set<String>> = repo.dockPinned
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val dockHidden: StateFlow<Set<String>> = repo.dockHidden
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setInDock(packageName: String, inDock: Boolean) {
        val isAuto = com.joeyos.app.ui.components.autoDockPackages(_installedApps.value)
            .any { it.packageName == packageName }
        AppLog.i("Settings", "${if (inDock) "Added to" else "Removed from"} dock: $packageName")
        viewModelScope.launch { repo.setInDock(packageName, inDock, isAuto) }
    }

    val lastLaunched: StateFlow<Map<String, Long>> = repo.lastLaunched
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun launchApp(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            AppLog.i("Launch", "Opening app $packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startApp(intent, packageName) }
                .onFailure { AppLog.e("Launch", "Couldn't open $packageName", it) }
            viewModelScope.launch {
                // Record launch for every system that maps to this package so dock sort updates.
                ALL_SYSTEMS
                    .filter { sys -> sys.knownPackages.any { packageName.startsWith(it) } }
                    .forEach { repo.recordLaunch(it.id) }
            }
        } else {
            AppLog.w("Launch", "Couldn't open $packageName: no launcher entry (not installed?)")
            Toast.makeText(context, "App not found", Toast.LENGTH_SHORT).show()
        }
    }

    fun setAssignment(systemId: String, packageName: String?) {
        AppLog.i("Settings", "Emulator for $systemId set to ${packageName ?: "not set"}")
        viewModelScope.launch { repo.setAssignment(systemId, packageName) }
    }

    fun recordLaunchForPackage(packageName: String) {
        // Launchers that start the game with an implicit intent don't name the package to
        // startGame, so note it here too for the rescan on the way back home.
        RecentGamesReader.noteLaunched(packageName)
        viewModelScope.launch {
            ALL_SYSTEMS
                .filter { sys -> sys.knownPackages.any { packageName.startsWith(it) } }
                .forEach { repo.recordLaunch(it.id) }
        }
    }

    // Bumped every time the recent-games cache is invalidated so the dock's cached
    // "last played" title badges know to refetch even when dockEntries itself is unchanged
    // (e.g. the same emulator stays at the top of the dock after playing another game).
    private val _recentGamesVersion = MutableStateFlow(0)
    val recentGamesVersion: StateFlow<Int> = _recentGamesVersion.asStateFlow()

    /**
     * Called from Activity.onResume. Only emulators started from JoeyOS since the last time home
     * was in front (plus any cached list past its time limit) are rescanned; the rest keep their
     * cached lists, so coming back from Settings or a non-emulator app scans nothing. The version
     * is only bumped when something was dropped, so the dock doesn't re-read for nothing either.
     */
    fun refreshRecentGamesAfterLaunch() {
        val dropped = RecentGamesReader.invalidateLaunched()
        if (dropped.isEmpty()) return
        _recentGamesVersion.update { it + 1 }
        viewModelScope.launch(Dispatchers.IO) {
            val installed = _installedApps.value.map { it.packageName }.toSet()
            val depth = recentDepth.value.takeIf { it > 0 } ?: 20
            dropped.filter { it in installed }.forEach { RecentGamesReader.readForPackage(it, depth) }
        }
    }

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()
    private var installedAppsLoaded = false
    private var packageChangeJob: kotlinx.coroutines.Job? = null

    /**
     * Loads the app list the first time only. After that it's kept up to date by
     * [onPackagesChanged] (from MainActivity's package receiver), so coming home doesn't
     * re-query the package manager every time.
     */
    fun ensureInstalledApps(context: Context) {
        if (installedAppsLoaded) return
        installedAppsLoaded = true
        loadInstalledApps(context)
    }

    /**
     * An app was installed, removed, updated or had a component switched on or off. Installing
     * over an existing app sends removed, added and replaced together, so wait a moment and
     * reload once. The package's cached recently played list is dropped too, since an update
     * can move where it saves.
     */
    fun onPackagesChanged(context: Context, packageName: String?) {
        if (packageName != null) RecentGamesReader.invalidatePackage(packageName)
        packageChangeJob?.cancel()
        packageChangeJob = viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            _recentGamesVersion.update { it + 1 }
            loadInstalledApps(context)
        }
    }

    /** Re-queries the installed apps (start-up, a package change, or Settings' refresh). */
    fun loadInstalledApps(context: Context) {
        installedAppsLoaded = true
        viewModelScope.launch {
            // Set display-scaled default on first run (sentinel value is 0).
            // 420dpi is the standard density for a 1080p (xxhdpi) Android phone → 72dp baseline.
            if (repo.dockIconSize.first() == 0) {
                val dpi = context.resources.displayMetrics.densityDpi
                val default = (72f * dpi / 420f).roundToInt().coerceIn(32, 120)
                repo.setDockIconSize(default)
            }

            val apps = withContext(Dispatchers.IO) { com.joeyos.app.data.loadInstalledApps(context) }
            _installedApps.value = apps
            autoPopulateAssignments(apps)
            // Pre-warm the title tables installed emulators use and the recent-games cache so
            // the popup opens instantly. Lists already cached are reused, not rescanned.
            launch(Dispatchers.IO) {
                GameDatabase.preWarm(apps.map { it.packageName })
                RecentGamesReader.preWarm(apps, recentDepth.value.takeIf { it > 0 } ?: 20)
            }
        }
    }

    private fun autoPopulateAssignments(apps: List<InstalledApp>) {
        if (apps.none { it.packageName.startsWith("com.retroarch") }) return
        viewModelScope.launch {
            val current = assignments.value
            ALL_SYSTEMS
                .filter { sys -> sys.knownPackages.any { it.startsWith("com.retroarch") } && sys.retroarchCores.isNotEmpty() }
                .forEach { sys ->
                    val existing = current[sys.id]
                    // Already assigned with a core — keep it.
                    if (existing != null && existing.contains("::")) return@forEach
                    repo.setAssignment(sys.id, RetroArchLauncher.assignmentFor(sys.retroarchCores.first()))
                }
        }
    }

    fun launchSystem(context: Context, systemId: String, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startGame(intent)
            viewModelScope.launch { repo.recordLaunch(systemId) }
        } else {
            Toast.makeText(context, "App not found: $packageName", Toast.LENGTH_SHORT).show()
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repo   = PreferencesRepository(context.applicationContext)
            val raRepo = RetroAchievementsRepository.get(context.applicationContext)
            // Infinite Backlog support was removed in 1.0.16; drop what it had saved.
            context.applicationContext.deleteSharedPreferences("infinitebacklog")
            return HomeViewModel(repo, raRepo) as T
        }
    }
}
