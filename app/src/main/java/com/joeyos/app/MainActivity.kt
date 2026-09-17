package com.joeyos.app

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.hardware.display.DisplayManager
import androidx.lifecycle.Lifecycle
import com.joeyos.app.data.SecondScreenController
import com.joeyos.app.data.SecondScreenState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import com.joeyos.app.data.GameDatabase
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.joeyos.app.ui.HomeScreen
import com.joeyos.app.ui.IntroScreen
import com.joeyos.app.ui.theme.JoeyOSTheme
import com.joeyos.app.ui.viewmodel.HomeViewModel
import com.joeyos.app.ui.controls.Control
import com.joeyos.app.ui.controls.ControlBus
import com.joeyos.app.ui.controls.Controls

class MainActivity : ComponentActivity() {

    private val vm: HomeViewModel by viewModels { HomeViewModel.Factory(applicationContext) }

    private var lastEventTime = -1L

    private fun hasPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private val prefs by lazy { getSharedPreferences("joeyos", MODE_PRIVATE) }

    private var introComplete        by mutableStateOf(false)
    private var hasStoragePermission by mutableStateOf(false)
    private var introHomeDone        by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.install(this)
        // Open the RetroAchievements login (a keystore call) in the background, before any screen asks.
        lifecycleScope.launch { com.joeyos.app.data.RetroAchievementsRepository.get(this@MainActivity).warmUp() }
        introComplete = hasPermission() && prefs.getBoolean("intro_done", false)
        hasStoragePermission = hasPermission()
        GameDatabase.init(this)
        enableEdgeToEdge()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, android.os.Handler(mainLooper))
        registerPackageReceiver()
        hideSystemBars()
        setContent {
            JoeyOSTheme {
                if (introComplete) {
                    HomeScreen(viewModel = vm)
                } else {
                    IntroScreen(
                        onGrantAccess = ::openStoragePermissionSettings,
                        onSetHomeApp  = ::openHomeAppSettings,
                        onContinue    = ::completeIntro,
                        grantDone     = hasStoragePermission,
                        homeDone      = introHomeDone
                    )
                }
            }
        }
    }

    /**
     * Full screen: no status or navigation bar, like a console's home screen. A swipe in from an
     * edge shows them for a moment. Re-applied whenever the window gets focus back, since a
     * notification shade, a permission screen or another app can leave them showing.
     */
    private fun hideSystemBars() {
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hasStoragePermission = hasPermission()
        introHomeDone = isDefaultHomeApp()
        if (introComplete) {
            // The app list is loaded once and then kept current by packageReceiver, and only the
            // emulators started since home was last in front get their recently played rescanned.
            vm.ensureInstalledApps(applicationContext)
            vm.refreshRecentGamesAfterLaunch()
            SecondScreenController.ensure(this)
        }
        SecondScreenState.backHome()
    }

    // Games on the other screen leave the home screen resumed, so "back home" is also when it
    // becomes the screen in front again.
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (isTopResumedActivity) { SecondScreenState.backHome(); SecondScreenController.onHomeInFront() }
    }

    // A screen plugged in or removed (HDMI, a dock): open or close the second screen to match.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = recheckScreens()
        override fun onDisplayRemoved(displayId: Int) = recheckScreens()
        override fun onDisplayChanged(displayId: Int) {}
    }

    private fun recheckScreens() {
        if (introComplete && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) SecondScreenController.ensure(this)
    }

    /**
     * Apps installed, removed, updated or switched on/off while JoeyOS is running: reload the
     * app list and drop that app's cached icon. This replaces reloading the list on every
     * return home. Package broadcasts are system-only, so exporting the receiver is safe.
     */
    private val packageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart
            if (pkg != null) com.joeyos.app.ui.components.AppIcons.evict(pkg)
            vm.onPackagesChanged(applicationContext, pkg)
        }
    }

    private fun registerPackageReceiver() {
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }
    }

    override fun onDestroy() {
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        runCatching { unregisterReceiver(packageReceiver) }
        super.onDestroy()
    }

    private fun completeIntro() {
        prefs.edit().putBoolean("intro_done", true).apply()
        introComplete = true
        vm.ensureInstalledApps(applicationContext)
    }

    private fun openStoragePermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun openHomeAppSettings() {
        startActivity(Intent("android.settings.HOME_SETTINGS"))
    }

    private fun isDefaultHomeApp(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == packageName
    }

    // ── Key events ────────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return dispatchNormalised(event)
    }

    /**
     * The new input layer (docs/input-rewrite.md). Hardware is cleaned up here and nothing else:
     *  - A / Select become DPAD centre and B becomes Back, so `clickable` and `BackHandler` answer
     *    the pad with no handler of ours. A is sent as a press on release, so holding it can never
     *    turn into a long-press — one button, one job.
     *  - The app's own buttons (Start, X, Y, shoulders, Menu) become one intent on the ControlBus.
     *  - Everything else — the D-pad, a remote's centre and Back — passes straight through to the
     *    focus system.
     */
    private fun dispatchNormalised(event: KeyEvent): Boolean {
        Controls.translate(event.keyCode)?.let { keyCode ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                // Only a release whose press we saw counts: quitting a game with A presses it in
                // the game, and the release lands here, which would reopen the focused tile.
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) centerDownTime = event.downTime
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && event.downTime == centerDownTime) {
                    centerDownTime = -1L
                    super.dispatchKeyEvent(event.withKeyCode(keyCode, KeyEvent.ACTION_DOWN))
                    super.dispatchKeyEvent(event.withKeyCode(keyCode, KeyEvent.ACTION_UP))
                }
            } else {
                super.dispatchKeyEvent(event.withKeyCode(keyCode, event.action))
            }
            return true
        }
        Controls.intentFor(event.keyCode)?.let { control ->
            // Both edges are consumed, so Android never synthesises a fallback from a button
            // we took (an unhandled Start's fallback is a confirm on whatever is focused).
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                // Some handhelds emit two keycodes for one press (the Odin 2 Portal's Start sends
                // Start and Menu, which both mean Options): Settings would open on the first and
                // close on the second, so it looked like it never opened. Ignore the same intent
                // if it repeats within a short window — no human presses this fast.
                val now = android.os.SystemClock.uptimeMillis()
                if (control == lastControl && now - lastControlAt < 300L) {
                    AppLog.i("Controls", "Ignored a repeated $control (${KeyEvent.keyCodeToString(event.keyCode)}) ${now - lastControlAt}ms after the last")
                    return true
                }
                lastControl = control; lastControlAt = now
                ControlBus.dispatch(control)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            KeyEvent.isGamepadButton(event.keyCode) && loggedUnknownKeys.add(event.keyCode)) {
            // A pad button we don't map: log it once so a new device names itself.
            AppLog.i("Controls", "Unmapped gamepad button ${KeyEvent.keyCodeToString(event.keyCode)} " +
                "from ${event.device?.name ?: "unknown device"}")
        }
        return super.dispatchKeyEvent(event)
    }

    private val loggedUnknownKeys = mutableSetOf<Int>()
    private var centerDownTime = -1L
    private var lastControl: Control? = null
    private var lastControlAt = 0L

    private fun KeyEvent.withKeyCode(keyCode: Int, action: Int) = KeyEvent(
        downTime, eventTime, action, keyCode, 0, metaState, deviceId, scanCode, flags, source
    )

    // ── Motion events (analog sticks) ─────────────────────────────────────────

    /**
     * Called BEFORE child views — catches events on devices that send them early.
     * Always delegates to super so Android can convert HAT axes → DPAD key events.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        handleStick(event)
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * Called AFTER child views didn't consume the event — catches events on devices
     * (like the Odin in Odin mode) where the event reaches the Activity last.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        handleStick(event)
        return super.onGenericMotionEvent(event)
    }

    /**
     * Shared stick handler. Checks every common horizontal axis so it works across
     * Xbox, PlayStation, Switch Pro, Odin, Retroid, Anbernic, and other layouts.
     * Uses eventTime to deduplicate if both dispatch paths fire for the same event.
     */
    private fun handleStick(event: MotionEvent) {
        if (!hasStoragePermission) return
        if (event.action != MotionEvent.ACTION_MOVE) return
        if (event.eventTime == lastEventTime) return
        lastEventTime = event.eventTime

        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))

        // The left stick is left to Android, which already turns an unhandled stick into D-pad
        // presses for the focus system. Analogue triggers become the L2/R2 intents once per pull
        // (edge, not repeat).
        val ltDown = lt > 0.5f
        val rtDown = rt > 0.5f
        if (ltDown && !triggerLHeld) ControlBus.dispatch(Control.PagePrev)
        if (rtDown && !triggerRHeld) ControlBus.dispatch(Control.PageNext)
        triggerLHeld = ltDown
        triggerRHeld = rtDown
    }

    private var triggerLHeld = false
    private var triggerRHeld = false
}
