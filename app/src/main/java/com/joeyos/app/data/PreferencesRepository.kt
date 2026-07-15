package com.joeyos.app.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "retro_launcher_prefs")

// Sealed class representing the three wallpaper modes
sealed class WallpaperState {
    data class Preset(val id: String) : WallpaperState()
    object Animated : WallpaperState()
    data class Custom(val uri: Uri) : WallpaperState()
}

enum class DockSortOrder   { RECENTLY_USED, ALPHABETICAL, ALPHABETICAL_DESC }
enum class DockCornerStyle { SQUARE, ROUNDED, CIRCLE }
enum class DockBgOpacity   { NONE, LOW, MEDIUM, HIGH }
enum class ClockFormat     { H24, H12, HIDDEN }
enum class DockTitleSize   { SMALL, MEDIUM, LARGE }

class PreferencesRepository(private val context: Context) {

    companion object {
        private val WALLPAPER_MODE     = stringPreferencesKey("wallpaper_mode")
        private val WALLPAPER_VALUE    = stringPreferencesKey("wallpaper_value")
        private val DOCK_ICON_SIZE     = stringPreferencesKey("dock_icon_size")
        private val DOCK_SORT_ORDER    = stringPreferencesKey("dock_sort_order")
        private val FAVORITE_GAME      = stringPreferencesKey("favorite_game")
        private val CUSTOM_WALLPAPERS  = stringPreferencesKey("custom_wallpapers_list")
        private val SHOW_RECENT_BADGE  = stringPreferencesKey("show_recent_badge")
        private val CLOCK_FORMAT       = stringPreferencesKey("clock_format")
        private val DOCK_CORNER_STYLE  = stringPreferencesKey("dock_corner_style")
        private val RECENT_DEPTH       = stringPreferencesKey("recent_depth")
        private val DOCK_BG_OPACITY    = stringPreferencesKey("dock_bg_opacity")
        private val DOCK_TITLE_SIZE    = stringPreferencesKey("dock_title_size")
        private fun assignmentKey(systemId: String)   = stringPreferencesKey("assignment_$systemId")
        private fun lastLaunchedKey(systemId: String) = stringPreferencesKey("launched_$systemId")
    }

    // Emits the current wallpaper state every time prefs change
    val wallpaperState: Flow<WallpaperState> = context.dataStore.data.map { prefs ->
        when (prefs[WALLPAPER_MODE]) {
            "animated" -> WallpaperState.Animated
            "custom" -> {
                val raw = prefs[WALLPAPER_VALUE].orEmpty()
                if (raw.isNotEmpty()) WallpaperState.Custom(Uri.parse(raw))
                else WallpaperState.Preset("space")
            }
            else -> WallpaperState.Preset(prefs[WALLPAPER_VALUE] ?: "space")
        }
    }

    // 0 = not yet initialised; ViewModel sets the default on first run
    val dockIconSize: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[DOCK_ICON_SIZE]?.toIntOrNull() ?: 0
    }

    val dockSortOrder: Flow<DockSortOrder> = context.dataStore.data.map { prefs ->
        when (prefs[DOCK_SORT_ORDER]) {
            "alphabetical"       -> DockSortOrder.ALPHABETICAL
            "alphabetical_desc"  -> DockSortOrder.ALPHABETICAL_DESC
            else                 -> DockSortOrder.RECENTLY_USED
        }
    }

    suspend fun setDockSortOrder(order: DockSortOrder) {
        val value = when (order) {
            DockSortOrder.RECENTLY_USED     -> "recently_used"
            DockSortOrder.ALPHABETICAL      -> "alphabetical"
            DockSortOrder.ALPHABETICAL_DESC -> "alphabetical_desc"
        }
        context.dataStore.edit { it[DOCK_SORT_ORDER] = value }
    }

    // Stored as "title|||path|||emulatorPackage|||lastPlayed|||corePath"
    val favoriteGame: Flow<RecentGame?> = context.dataStore.data.map { prefs ->
        val raw = prefs[FAVORITE_GAME] ?: return@map null
        val parts = raw.split("|||")
        if (parts.size >= 3) RecentGame(
            title           = parts[0],
            path            = parts[1],
            emulatorPackage = parts[2],
            lastPlayed      = parts.getOrNull(3)?.toLongOrNull() ?: 0L,
            corePath        = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }
        )
        else null
    }

    suspend fun setFavoriteGame(game: RecentGame?) {
        context.dataStore.edit { prefs ->
            if (game == null) prefs.remove(FAVORITE_GAME)
            else prefs[FAVORITE_GAME] = "${game.title}|||${game.path}|||${game.emulatorPackage}|||${game.lastPlayed}|||${game.corePath ?: ""}"
        }
    }

    val lastLaunched: Flow<Map<String, Long>> = context.dataStore.data.map { prefs ->
        ALL_SYSTEMS.mapNotNull { sys ->
            val ts = prefs[lastLaunchedKey(sys.id)]?.toLongOrNull()
            if (ts != null) sys.id to ts else null
        }.toMap()
    }

    suspend fun recordLaunch(systemId: String) {
        context.dataStore.edit { it[lastLaunchedKey(systemId)] = System.currentTimeMillis().toString() }
    }

    val customWallpapers: Flow<List<Uri>> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_WALLPAPERS]?.split("\n")
            ?.filter { it.isNotEmpty() }
            ?.map { Uri.parse(it) }
            ?: emptyList()
    }

    suspend fun addCustomWallpaper(uri: Uri) {
        context.dataStore.edit { prefs ->
            val current = prefs[CUSTOM_WALLPAPERS]?.split("\n")?.filter { it.isNotEmpty() }?.toMutableList() ?: mutableListOf()
            if (uri.toString() !in current) current.add(uri.toString())
            prefs[CUSTOM_WALLPAPERS] = current.joinToString("\n")
        }
    }

    suspend fun removeCustomWallpaper(uri: Uri) {
        context.dataStore.edit { prefs ->
            val uriStr = uri.toString()
            val current = prefs[CUSTOM_WALLPAPERS]?.split("\n")?.filter { it.isNotEmpty() && it != uriStr }?.toMutableList() ?: mutableListOf()
            prefs[CUSTOM_WALLPAPERS] = current.joinToString("\n")
            if (prefs[WALLPAPER_MODE] == "custom" && prefs[WALLPAPER_VALUE] == uriStr) {
                prefs[WALLPAPER_MODE]  = "preset"
                prefs[WALLPAPER_VALUE] = "space"
            }
        }
    }

    suspend fun setDockIconSize(size: Int) {
        context.dataStore.edit { it[DOCK_ICON_SIZE] = size.toString() }
    }

    // Emits the full systemId -> packageName map every time any assignment changes
    val allAssignments: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        ALL_SYSTEMS.mapNotNull { sys ->
            val pkg = prefs[assignmentKey(sys.id)]?.takeIf { it.isNotEmpty() }
            if (pkg != null) sys.id to pkg else null
        }.toMap()
    }

    suspend fun setWallpaper(state: WallpaperState) {
        context.dataStore.edit { prefs ->
            when (state) {
                is WallpaperState.Preset -> {
                    prefs[WALLPAPER_MODE]  = "preset"
                    prefs[WALLPAPER_VALUE] = state.id
                }
                is WallpaperState.Animated -> {
                    prefs[WALLPAPER_MODE]  = "animated"
                    prefs[WALLPAPER_VALUE] = ""
                }
                is WallpaperState.Custom -> {
                    prefs[WALLPAPER_MODE]  = "custom"
                    prefs[WALLPAPER_VALUE] = state.uri.toString()
                }
            }
        }
    }

    suspend fun setAssignment(systemId: String, packageName: String?) {
        context.dataStore.edit { prefs ->
            prefs[assignmentKey(systemId)] = packageName.orEmpty()
        }
    }

    val showRecentBadge: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SHOW_RECENT_BADGE] != "false"
    }

    val clockFormat: Flow<ClockFormat> = context.dataStore.data.map { prefs ->
        when (prefs[CLOCK_FORMAT]) {
            "24h" -> ClockFormat.H24
            "12h" -> ClockFormat.H12
            else  -> ClockFormat.HIDDEN
        }
    }

    val dockCornerStyle: Flow<DockCornerStyle> = context.dataStore.data.map { prefs ->
        when (prefs[DOCK_CORNER_STYLE]) {
            "square" -> DockCornerStyle.SQUARE
            "circle" -> DockCornerStyle.CIRCLE
            else     -> DockCornerStyle.ROUNDED
        }
    }

    val recentDepth: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[RECENT_DEPTH]?.toIntOrNull() ?: 20
    }

    val dockBgOpacity: Flow<DockBgOpacity> = context.dataStore.data.map { prefs ->
        when (prefs[DOCK_BG_OPACITY]) {
            "low"    -> DockBgOpacity.LOW
            "medium" -> DockBgOpacity.MEDIUM
            "high"   -> DockBgOpacity.HIGH
            else     -> DockBgOpacity.NONE
        }
    }

    suspend fun setShowRecentBadge(show: Boolean) {
        context.dataStore.edit { it[SHOW_RECENT_BADGE] = if (show) "true" else "false" }
    }

    suspend fun setClockFormat(fmt: ClockFormat) {
        val v = when (fmt) { ClockFormat.H12 -> "12h"; ClockFormat.HIDDEN -> "off"; else -> "24h" }
        context.dataStore.edit { it[CLOCK_FORMAT] = v }
    }

    suspend fun setDockCornerStyle(style: DockCornerStyle) {
        val v = when (style) {
            DockCornerStyle.SQUARE  -> "square"
            DockCornerStyle.ROUNDED -> "rounded"
            DockCornerStyle.CIRCLE  -> "circle"
        }
        context.dataStore.edit { it[DOCK_CORNER_STYLE] = v }
    }

    suspend fun setRecentDepth(depth: Int) {
        context.dataStore.edit { it[RECENT_DEPTH] = depth.toString() }
    }

    suspend fun setDockBgOpacity(opacity: DockBgOpacity) {
        val v = when (opacity) {
            DockBgOpacity.NONE   -> "none"
            DockBgOpacity.LOW    -> "low"
            DockBgOpacity.MEDIUM -> "medium"
            DockBgOpacity.HIGH   -> "high"
        }
        context.dataStore.edit { it[DOCK_BG_OPACITY] = v }
    }

    val dockTitleSize: Flow<DockTitleSize> = context.dataStore.data.map { prefs ->
        when (prefs[DOCK_TITLE_SIZE]) {
            "small" -> DockTitleSize.SMALL
            "large" -> DockTitleSize.LARGE
            else    -> DockTitleSize.MEDIUM
        }
    }

    suspend fun setDockTitleSize(size: DockTitleSize) {
        val v = when (size) {
            DockTitleSize.SMALL  -> "small"
            DockTitleSize.MEDIUM -> "medium"
            DockTitleSize.LARGE  -> "large"
        }
        context.dataStore.edit { it[DOCK_TITLE_SIZE] = v }
    }
}
