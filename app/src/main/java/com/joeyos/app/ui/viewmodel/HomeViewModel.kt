package com.joeyos.app.ui.viewmodel

import android.content.Context
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
import com.joeyos.app.data.InfiniteBacklogRepository
import com.joeyos.app.data.RetroAchievementsRepository
import com.joeyos.app.data.RetroArchLauncher
import com.joeyos.app.data.WallpaperState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

sealed class ControllerEvent {
    object L1         : ControllerEvent()
    object R1         : ControllerEvent()
    object L2         : ControllerEvent()
    object R2         : ControllerEvent()
    object A          : ControllerEvent()
    object LongPressA : ControllerEvent()
    object X          : ControllerEvent()
    object Y          : ControllerEvent()
    object B          : ControllerEvent()
    object Start      : ControllerEvent()
    object DpadLeft   : ControllerEvent()
    object DpadRight  : ControllerEvent()
    object DpadUp     : ControllerEvent()
    object DpadDown   : ControllerEvent()
}

class HomeViewModel(
    private val repo: PreferencesRepository,
    val raRepo: RetroAchievementsRepository,
    val ibRepo: InfiniteBacklogRepository
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

    val lastLaunched: StateFlow<Map<String, Long>> = repo.lastLaunched
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val controllerEvents = MutableSharedFlow<ControllerEvent>(extraBufferCapacity = 8)

    // Dock selection index: -1 = nothing, 0 = star, 1..N = systems, N+1 = add tile.
    // Kept in ViewModel so L1/R1 update it synchronously before the UI recomposes.
    private val _selectedDockIndex = MutableStateFlow(-1)
    val selectedDockIndex: StateFlow<Int> = _selectedDockIndex.asStateFlow()

    // Total dock entries: 2 pinned tiles (Favorite + Recent) + one per unique installed emulator.
    private val dockItemCount: Int
        get() {
            val installed = _installedApps.value.map { it.packageName }
            val emulators = ALL_SYSTEMS
                .flatMap { sys -> sys.knownPackages.mapNotNull { k -> installed.firstOrNull { it.startsWith(k) } } }
                .toSet().size
            return 2 + emulators
        }

    /** Called from Activity.dispatchKeyEvent / onGenericMotionEvent — returns true if consumed. */
    fun onControllerKey(keyCode: Int): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_BUTTON_L1 -> {
                viewModelScope.launch { controllerEvents.emit(ControllerEvent.L1) }
                return true
            }
            android.view.KeyEvent.KEYCODE_BUTTON_R1 -> {
                viewModelScope.launch { controllerEvents.emit(ControllerEvent.R1) }
                return true
            }
            android.view.KeyEvent.KEYCODE_BUTTON_L2 -> {
                viewModelScope.launch { controllerEvents.emit(ControllerEvent.L2) }
                return true
            }
            android.view.KeyEvent.KEYCODE_BUTTON_R2 -> {
                viewModelScope.launch { controllerEvents.emit(ControllerEvent.R2) }
                return true
            }
            else -> {
                val event = when (keyCode) {
                    android.view.KeyEvent.KEYCODE_BUTTON_A     -> ControllerEvent.A
                    android.view.KeyEvent.KEYCODE_BUTTON_X     -> ControllerEvent.X
                    android.view.KeyEvent.KEYCODE_BUTTON_Y     -> ControllerEvent.Y
                    android.view.KeyEvent.KEYCODE_BUTTON_B     -> ControllerEvent.B
                    android.view.KeyEvent.KEYCODE_BUTTON_START -> ControllerEvent.Start
                    else -> return false
                }
                viewModelScope.launch { controllerEvents.emit(event) }
                return true
            }
        }
    }

    /**
     * Called from Activity for DPAD_LEFT / DPAD_RIGHT. Updates dock highlight but does NOT
     * consume the event so Compose focus traversal still works inside Settings / AppDrawer.
     */
    fun onDpadHorizontal(direction: Int) {
        val event = if (direction < 0) ControllerEvent.DpadLeft else ControllerEvent.DpadRight
        viewModelScope.launch { controllerEvents.emit(event) }
    }

    fun onDpadVertical(direction: Int) {
        val event = if (direction < 0) ControllerEvent.DpadUp else ControllerEvent.DpadDown
        viewModelScope.launch { controllerEvents.emit(event) }
    }

    fun onLongPressA() {
        viewModelScope.launch { controllerEvents.emit(ControllerEvent.LongPressA) }
    }

    fun setSelectedDockIndex(idx: Int) {
        _selectedDockIndex.value = idx
    }

    fun shiftDockIndex(direction: Int) {
        _selectedDockIndex.update { cur ->
            if (direction < 0) {
                if (cur < 0) 0 else (cur - 1).coerceAtLeast(0)
            } else {
                if (cur < 0) 0 else (cur + 1).coerceAtMost(dockItemCount - 1)
            }
        }
    }

    fun launchApp(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            viewModelScope.launch {
                // Record launch for every system that maps to this package so dock sort updates.
                ALL_SYSTEMS
                    .filter { sys -> sys.knownPackages.any { packageName.startsWith(it) } }
                    .forEach { repo.recordLaunch(it.id) }
            }
        } else {
            Toast.makeText(context, "App not found", Toast.LENGTH_SHORT).show()
        }
    }

    fun setAssignment(systemId: String, packageName: String?) {
        viewModelScope.launch { repo.setAssignment(systemId, packageName) }
    }

    fun recordLaunchForPackage(packageName: String) {
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

    /** Called from Activity.onResume when returning from an emulator. */
    fun invalidateAndPreWarmRecentGames() {
        _recentGamesVersion.update { it + 1 }
        viewModelScope.launch(Dispatchers.IO) {
            RecentGamesReader.invalidateCache()
            val apps = _installedApps.value
            if (apps.isNotEmpty()) {
                RecentGamesReader.preWarm(apps, recentDepth.value.takeIf { it > 0 } ?: 20)
            }
        }
    }

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    fun loadInstalledApps(context: Context) {
        viewModelScope.launch {
            // Set display-scaled default on first run (sentinel value is 0).
            // 420dpi is the standard density for a 1080p (xxhdpi) Android phone → 72dp baseline.
            if (repo.dockIconSize.first() == 0) {
                val dpi = context.resources.displayMetrics.densityDpi
                val default = (72f * dpi / 420f).roundToInt().coerceIn(32, 120)
                repo.setDockIconSize(default)
            }

            val apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                pm.queryIntentActivities(intent, 0)
                    .map { info ->
                        InstalledApp(
                            packageName = info.activityInfo.packageName,
                            label = info.loadLabel(pm).toString()
                        )
                    }
                    .filter { it.packageName != context.packageName }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }
            }
            _installedApps.value = apps
            autoPopulateAssignments(apps)
            // Pre-warm game DB and recent-games cache so the popup opens instantly.
            launch(Dispatchers.IO) {
                GameDatabase.preWarm()
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
            context.startActivity(intent)
            viewModelScope.launch { repo.recordLaunch(systemId) }
        } else {
            Toast.makeText(context, "App not found: $packageName", Toast.LENGTH_SHORT).show()
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repo   = PreferencesRepository(context.applicationContext)
            val raRepo = RetroAchievementsRepository(context.applicationContext)
            val ibRepo = InfiniteBacklogRepository(context.applicationContext)
            return HomeViewModel(repo, raRepo, ibRepo) as T
        }
    }
}
