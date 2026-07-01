package com.joeyos.app

import android.content.Intent
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.joeyos.app.ui.HomeScreen
import com.joeyos.app.ui.IntroScreen
import com.joeyos.app.ui.theme.JoeyOSTheme
import com.joeyos.app.ui.viewmodel.HomeViewModel
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : ComponentActivity() {

    private val vm: HomeViewModel by viewModels { HomeViewModel.Factory(applicationContext) }

    private var lastStickMs   = 0L
    private var lastEventTime = -1L
    private var longPressAJob: Job? = null

    private fun hasPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private val prefs by lazy { getSharedPreferences("joeyos", MODE_PRIVATE) }

    private var introComplete        by mutableStateOf(false)
    private var hasStoragePermission by mutableStateOf(false)
    private var introSelectedIdx     by mutableIntStateOf(0)
    private var introHomeDone        by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        introComplete = hasPermission() && prefs.getBoolean("intro_done", false)
        hasStoragePermission = hasPermission()
        GameDatabase.init(this)
        enableEdgeToEdge()
        setContent {
            JoeyOSTheme {
                if (introComplete) {
                    HomeScreen(viewModel = vm)
                } else {
                    IntroScreen(
                        onGrantAccess = ::openStoragePermissionSettings,
                        onSetHomeApp  = ::openHomeAppSettings,
                        onContinue    = ::completeIntro,
                        selectedIdx   = introSelectedIdx,
                        grantDone     = hasStoragePermission,
                        homeDone      = introHomeDone
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasStoragePermission = hasPermission()
        introHomeDone = isDefaultHomeApp()
        if (introComplete) {
            vm.loadInstalledApps(this)
            vm.invalidateAndPreWarmRecentGames()
        }
    }

    private fun completeIntro() {
        prefs.edit().putBoolean("intro_done", true).apply()
        introComplete = true
        vm.loadInstalledApps(this)
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
        // On the intro screen handle D-pad navigation and A to activate.
        if (!introComplete) {
            val maxIdx = if (hasStoragePermission) 2 else 1
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_BUTTON_L1 -> {
                        introSelectedIdx = (introSelectedIdx - 1).coerceAtLeast(0)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_BUTTON_R1 -> {
                        introSelectedIdx = (introSelectedIdx + 1).coerceAtMost(maxIdx)
                        return true
                    }
                }
            }
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A && event.action == KeyEvent.ACTION_UP) {
                when (introSelectedIdx) {
                    0 -> openStoragePermissionSettings()
                    1 -> openHomeAppSettings()
                    2 -> completeIntro()
                }
            }
            return true
        }

        // Long-press A: start a timer on DOWN; if it fires before UP → LongPressA.
        // If UP arrives first → short press → emit regular A.
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        longPressAJob?.cancel()
                        longPressAJob = lifecycleScope.launch {
                            delay(500.milliseconds)
                            vm.onLongPressA()
                            longPressAJob = null
                        }
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    if (longPressAJob?.isActive == true) {
                        longPressAJob?.cancel()
                        longPressAJob = null
                        vm.onControllerKey(KeyEvent.KEYCODE_BUTTON_A)
                    }
                    return true
                }
            }
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT  -> { vm.onDpadHorizontal(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { vm.onDpadHorizontal(+1); return true }
                KeyEvent.KEYCODE_DPAD_UP    -> { vm.onDpadVertical(-1);   return true }
                KeyEvent.KEYCODE_DPAD_DOWN  -> { vm.onDpadVertical(+1);   return true }
            }
            if (vm.onControllerKey(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

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

        val now = System.currentTimeMillis()

        // Triggers → L2/R2 (AXIS_LTRIGGER=17, AXIS_RTRIGGER=18; also check AXIS_BRAKE/GAS)
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        if (now - lastStickMs > 180) {
            if (lt > 0.5f) { vm.onControllerKey(KeyEvent.KEYCODE_BUTTON_L2); lastStickMs = now }
            else if (rt > 0.5f) { vm.onControllerKey(KeyEvent.KEYCODE_BUTTON_R2); lastStickMs = now }
        }

        // Sticks → L1/R1 (dock step navigation)
        val x = floatArrayOf(
            event.getAxisValue(MotionEvent.AXIS_X),
            event.getAxisValue(MotionEvent.AXIS_Z),
            event.getAxisValue(MotionEvent.AXIS_RX),
            event.getAxisValue(MotionEvent.AXIS_RUDDER),
            event.getAxisValue(MotionEvent.AXIS_WHEEL),
        ).maxByOrNull { abs(it) } ?: 0f

        if (abs(x) > 0.35f && now - lastStickMs > 180) {
            val key = if (x < 0f) KeyEvent.KEYCODE_BUTTON_L1 else KeyEvent.KEYCODE_BUTTON_R1
            vm.onControllerKey(key)
            lastStickMs = now
        }
    }
}
