package com.joeyos.app.data

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.joeyos.app.AppLog
import com.joeyos.app.MainActivity
import com.joeyos.app.SecondScreenActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Dual-screen handhelds (AYN Thor, AYANEO Pocket DS, …). Ported from Chameleon's DisplayTargets,
 * with the panel reworked the way Mjolnir does it: the second screen is JoeyOS's own activity
 * launched onto the other display, not a Presentation owned by the home screen. A visible
 * activity keeps itself alive through a whole game; a Presentation dies with the home screen if
 * Android reclaims it in the background.
 */

private const val TAG = "SecondScreen"

/** Which displays there are, named by what they are to you rather than by index. */
object DisplayTargets {

    /** The display [context]'s window is on (the one JoeyOS draws on), or the default. */
    fun currentDisplayId(context: Context): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { context.display?.displayId }.getOrNull()?.let { return it }
        }
        @Suppress("DEPRECATION")
        val legacy = (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)
            ?.defaultDisplay?.displayId
        return legacy ?: Display.DEFAULT_DISPLAY
    }

    /**
     * The other screen: the first display that isn't the one JoeyOS is on. Not "index 1": a
     * Thor treats its top screen as primary, an AYANEO Pocket DS its bottom one (Chameleon).
     * Ids change across reboots, so this is looked up fresh every time, never stored.
     */
    fun otherDisplay(context: Context): Display? {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        val here = currentDisplayId(context)
        return manager.displays.firstOrNull { it.displayId != here && it.state != Display.STATE_OFF }
            ?: manager.displays.firstOrNull { it.displayId != here }
    }

    fun hasSecondScreen(context: Context): Boolean = otherDisplay(context) != null

    fun optionsFor(displayId: Int): android.os.Bundle =
        ActivityOptions.makeBasic().apply { launchDisplayId = displayId }.toBundle()
}

/** The second-screen settings. Plain prefs, so launch code anywhere can read them at once. */
object SecondScreenPrefs {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("second_screen", Context.MODE_PRIVATE)

    /** Show JoeyOS on the other screen. On by default: a dual-screen owner wants it. */
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("enabled", on).apply()

    /** Hide a locked achievement's name and description until you tap it. */
    fun hideSpoilers(context: Context) = prefs(context).getBoolean("hide_spoilers", false)
    fun setHideSpoilers(context: Context, on: Boolean) = prefs(context).edit().putBoolean("hide_spoilers", on).apply()

    /** Games open on the other screen instead of the one JoeyOS is on. */
    fun gamesOnOther(context: Context) = prefs(context).getBoolean("games_on_other", false)
    fun setGamesOnOther(context: Context, on: Boolean) = prefs(context).edit().putBoolean("games_on_other", on).apply()

    /** Apps set to open on the other screen (App Drawer › app options). */
    fun otherScreenApps(context: Context): Set<String> =
        prefs(context).getStringSet("other_screen_apps", emptySet()).orEmpty()
    fun setOnOtherScreen(context: Context, pkg: String, on: Boolean) {
        val set = otherScreenApps(context).toMutableSet().apply { if (on) add(pkg) else remove(pkg) }
        prefs(context).edit().putStringSet("other_screen_apps", set).apply()
    }
}

/**
 * Emulators that draw on the second screen themselves (DS, 3DS, Wii U gamepad). JoeyOS closes
 * its own second screen before starting one of these and reopens it back on the home screen,
 * rather than sitting over the emulator's screen (a gap Chameleon left open).
 */
object SecondScreenOwners {
    private val prefixes = listOf(
        "me.magnum.melon",        // melonDS, melonDS Dual DS
        "com.dsemu.drastic",      // DraStic
        "org.azahar_emu",         // Azahar (3DS)
        "org.citra",              // Citra and forks under its id
        "io.github.lime3ds",
        "io.github.mandarine3ds",
        "io.github.borked3ds",
        "info.cemu",              // Cemu (Wii U gamepad)
    )
    fun owns(pkg: String?): Boolean = pkg != null && prefixes.any { pkg.startsWith(it) }
}

/**
 * Whether a game is running: when JoeyOS last started one, cleared when the home screen is back.
 * The second screen watches this to switch to the game's achievements. Same process, so a plain
 * flow is enough (the second screen is JoeyOS's own activity).
 */
object SecondScreenState {
    /** A game JoeyOS started: when, and its name when JoeyOS knows it (Recently Played, X). */
    data class Session(val startedAt: Long, val title: String?)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()
    @Volatile private var pendingTitle: String? = null

    /** Called just before a known game is launched, so the second screen can name it at once. */
    fun willLaunch(title: String) { pendingTitle = title }
    fun gameStarted() {
        _session.value = Session(System.currentTimeMillis(), pendingTitle)
        pendingTitle = null
    }
    fun backHome() { _session.value = null; pendingTitle = null }

    /**
     * Someone is typing in JoeyOS. The Thor shows its keyboard on the bottom screen, under this
     * second screen (found on device, and a window flag didn't cure it), so while this is true the
     * second screen turns see-through and lets touches through to the keyboard.
     */
    private val _typing = MutableStateFlow(false)
    val typing: StateFlow<Boolean> = _typing.asStateFlow()
    fun setTyping(on: Boolean) { _typing.value = on }
}

/** Opens, keeps and closes the second screen. Driven by MainActivity. */
object SecondScreenController {
    private var lastOpenAt = 0L

    /**
     * Makes sure the second screen is showing when it should be: enabled, a second display
     * present, and not already open. Called when the home screen resumes and when a display
     * comes or goes.
     */
    fun ensure(home: Activity) {
        val other = DisplayTargets.otherDisplay(home)
        // Only while JoeyOS's home is on the main screen. Found in the log: home was running on
        // the Thor's bottom screen (opened from there), and the "second" screen went on the top one.
        val onMain = DisplayTargets.currentDisplayId(home) == Display.DEFAULT_DISPLAY
        val want = SecondScreenPrefs.enabled(home) && other != null && onMain
        val open = SecondScreenActivity.current
        when {
            !want -> open?.finish()
            open != null && open.displayId() == other!!.displayId -> {}
            // Handing focus back resumes the home screen, which calls this again before the new
            // screen has registered itself: don't open a second one.
            System.currentTimeMillis() - lastOpenAt < 3_000 -> {}
            else -> {
                lastOpenAt = System.currentTimeMillis()
                open?.finish()
                AppLog.i(TAG, "Opening the second screen on display ${other!!.displayId} (${other.name})")
                runCatching {
                    home.startActivity(
                        Intent(home, SecondScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        DisplayTargets.optionsFor(other.displayId)
                    )
                }.onFailure { AppLog.e(TAG, "Couldn't open the second screen", it); return }
                // The screen started last gets the controller, and that just became the second
                // screen (whose window can't take it). Hand it straight back to the home screen
                // by bringing it to the front again: the launch-order trick Mjolnir uses.
                val homeDisplay = DisplayTargets.currentDisplayId(home)
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching {
                        home.startActivity(
                            Intent(home, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                            DisplayTargets.optionsFor(homeDisplay)
                        )
                    }
                }, 350)
            }
        }
    }

    fun close() {
        SecondScreenActivity.current?.finish()
    }
}

/**
 * Starts a game on the screen games are set to open on. Every emulator launcher goes through
 * this, so the choice applies to all of them. An emulator that uses the second screen itself
 * gets it: JoeyOS's own second screen is closed first.
 */
fun Context.startGame(intent: Intent) {
    val pkg = intent.`package` ?: intent.component?.packageName
    SecondScreenState.gameStarted()
    if (SecondScreenOwners.owns(pkg)) {
        AppLog.i(TAG, "$pkg uses the second screen itself; closing JoeyOS's")
        SecondScreenController.close()
    }
    val other = if (SecondScreenPrefs.gamesOnOther(this) && !SecondScreenOwners.owns(pkg))
        DisplayTargets.otherDisplay(this) else null
    if (other != null) startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), DisplayTargets.optionsFor(other.displayId))
    else startActivity(intent)
}

/** Starts an app: on the other screen if it's set to open there, else like a game if it's an emulator. */
fun Context.startApp(intent: Intent, pkg: String) {
    val other = if (pkg in SecondScreenPrefs.otherScreenApps(this)) DisplayTargets.otherDisplay(this) else null
    when {
        other != null -> startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), DisplayTargets.optionsFor(other.displayId))
        ALL_SYSTEMS.any { sys -> sys.knownPackages.any { pkg.startsWith(it) } } -> startGame(intent)
        else -> startActivity(intent)
    }
}
