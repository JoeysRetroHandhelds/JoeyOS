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
 * Its window can't take key focus (FLAG_NOT_FOCUSABLE). Touch still works, but the controller
 * always stays with the game: tapping this screen doesn't move focus here, because Android only
 * moves it on a tap into a window that can receive keys.
 */
class SecondScreenActivity : ComponentActivity() {

    companion object {
        private var ref: WeakReference<SecondScreenActivity>? = null
        /** The open second screen, if there is one. */
        val current: SecondScreenActivity? get() = ref?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
    }

    fun displayId(): Int = DisplayTargets.currentDisplayId(this)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {}
        // Android moves an activity to the main screen when its display goes (HDMI unplugged):
        // close instead of covering the home screen.
        override fun onDisplayRemoved(displayId: Int) { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ref = WeakReference(this)
        AppLog.i("SecondScreen", "Second screen open on display ${displayId()}")
        // ALT_FOCUSABLE_IM with NOT_FOCUSABLE puts this window *behind* the keyboard. Without it a
        // non-focusable window sits on top of the keyboard, so typing on the home screen (the
        // RetroAchievements login) had its keyboard hidden under this screen on the Thor.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
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
