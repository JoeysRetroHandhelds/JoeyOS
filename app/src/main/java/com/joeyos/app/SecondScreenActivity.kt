package com.joeyos.app

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.joeyos.app.data.DisplayTargets
import com.joeyos.app.data.SecondScreenState
import kotlinx.coroutines.launch
import com.joeyos.app.ui.components.SecondScreenContent
import com.joeyos.app.ui.theme.JoeyOSTheme
import java.lang.ref.WeakReference

/**
 * JoeyOS on the second screen of a dual-screen handheld, opened by [com.joeyos.app.data.SecondScreenController].
 *
 * Its own activity on the other display (the way Mjolnir does it), not a Presentation owned by the
 * home screen: while it's visible it keeps itself alive for the whole game, so a guide or the
 * achievements can't vanish because Android reclaimed the home screen in the background.
 *
 * Its window can take buttons. It used to refuse them (FLAG_NOT_FOCUSABLE) so the controller
 * always stayed with the game, but then the bottom screen's own back arrow sent Back to a window
 * that couldn't take it: Android waited, and the next tap closed JoeyOS as not responding (found
 * on the Thor). Now it takes the controller like any app, and the handheld decides which screen
 * gets it (the Thor locks it to the top, the bottom, or whichever screen you tap). Back works
 * here and never closes this screen.
 */
class SecondScreenActivity : ComponentActivity() {

    companion object {
        private var ref: WeakReference<SecondScreenActivity>? = null
        /** The open second screen, if there is one. */
        val current: SecondScreenActivity? get() = ref?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
    }

    fun displayId(): Int = DisplayTargets.currentDisplayId(this)
    private var shownOn = -1

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {}
        // Android moves an activity to the main screen when its display goes (HDMI unplugged):
        // close instead of covering the home screen. Only for our own display: a screen recording
        // or a cast ending removes a display too.
        override fun onDisplayRemoved(displayId: Int) { if (displayId == shownOn) finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ref = WeakReference(this)
        shownOn = displayId()
        com.joeyos.app.data.SecondScreenController.onOpened()
        AppLog.i("SecondScreen", "Second screen open on display ${displayId()}")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Back steps back inside this screen (its pages handle it); with nothing left, it does
        // nothing rather than closing the second screen.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        setContent { JoeyOSTheme { SecondScreenContent() } }
        // While typing on the home screen, get out of the keyboard's way (see SecondScreenState.typing).
        lifecycleScope.launch {
            SecondScreenState.typing.collect { typing ->
                window.attributes = window.attributes.apply { alpha = if (typing) 0f else 1f }
                if (typing) window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            }
        }
    }

    /**
     * Typing here (Find in a guide, a site's search box). The window can always take the keyboard
     * now, so there's nothing to switch; kept so the pages can say when they're typing.
     */
    @Suppress("UNUSED_PARAMETER")
    fun allowTyping(on: Boolean) {}

    /**
     * Dims this screen's backlight (not just the pixels) to save power and avoid burn-in when it's
     * idle during a game. BRIGHTNESS_OVERRIDE_NONE hands brightness back to the system.
     */
    fun setDim(on: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    // In front: the first time after opening, the home screen gets the front back (see
    // SecondScreenController.onInFront).
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (isTopResumedActivity) com.joeyos.app.data.SecondScreenController.onInFront(this)
    }

    override fun onResume() {
        super.onResume()
        // Only one screen left (its own went away while JoeyOS wasn't watching): not ours to cover.
        if ((getSystemService(DISPLAY_SERVICE) as DisplayManager).displays.size < 2) finish()
    }

    override fun onDestroy() {
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        if (ref?.get() === this) ref = null
        AppLog.i("SecondScreen", "Second screen closed")
        super.onDestroy()
    }
}
