package com.joeyos.app.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The one popup shell: a real Dialog window, per the Android dialogs guidance and what finally
 * worked in Chameleon's windows rewrite. A real window contains focus, answers Back on its own,
 * and hands focus back to the screen underneath when it closes — all things an overlay drawn
 * inside the home screen has to fake by hand.
 *
 * Two details make it controller-correct:
 *  - A new dialog window starts in touch mode, where Compose hides focus, so nothing looks
 *    selected until a second press. Asking for keyboard input mode on open fixes that, then
 *    focus lands on the first focusable item.
 *  - The dialog's window gets keys before MainActivity does, so the pad normalisation is done
 *    here too: A / Select press the focused item, B closes, and every other pad button is
 *    swallowed so Android can't synthesise a fallback (Start's fallback is a confirm).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun JoeyDialog(
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    /**
     * Re-lands focus on the first item whenever this changes — for a popup whose rows arrive
     * after it opens (a list still loading), so focus lands once there is something to hold it.
     */
    focusKey: Any? = Unit,
    /** A specific item to land on instead of the first (a picker's current choice). */
    initialFocus: FocusRequester? = null,
    /** Start / Menu: the focused item's options, where the popup has any. */
    onOptions: (() -> Unit)? = null,
    /**
     * Offered every B / Back before the popup closes; return true to consume it. A popup with a
     * text field uses this to leave typing first, so cancel steps back one level at a time.
     */
    interceptBack: (() -> Boolean)? = null,
    /** Any other pad button (L1/R1, …), offered on its down edge; both edges are swallowed. */
    onPadButton: ((keyCode: Int) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    // Text fields inside register here to take B / Back while they're being typed in.
    val backRegistry = remember { DialogBackRegistry() }
    Dialog(
        onDismissRequest = { if (interceptBack?.invoke() != true && !backRegistry.handle()) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible
        )
    ) {
        val view = LocalView.current
        // A dialog is its own window with its own bar state: without this, opening a popup
        // brings the status and navigation bars back over the full-screen home screen.
        androidx.compose.runtime.SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
                androidx.core.view.WindowInsetsControllerCompat(window, view).apply {
                    systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        val inputMode = LocalInputModeManager.current
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(focusKey) {
            withFrameNanos { }
            inputMode.requestInputMode(InputMode.Keyboard)
            // The named item when there is one. requestFocus throws if it isn't attached, so fall
            // back to moving focus into the window: view.requestFocus() alone only focuses the
            // window's view (it already has that), not the first item inside it — found on device.
            val landed = initialFocus != null && runCatching { initialFocus.requestFocus() }.isSuccess
            if (!landed) {
                view.requestFocus()
                focusManager.moveFocus(FocusDirection.Next)
            }
        }
        Box(
            modifier = Modifier.onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                when (native.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT -> {
                        // Press on release, as a centre tap, so the focused item's clickable
                        // answers it. Posted so it isn't dispatched from inside this dispatch.
                        if (native.action == KeyEvent.ACTION_UP && !native.isCanceled) {
                            view.post {
                                view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                                view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                            }
                        }
                        true
                    }
                    KeyEvent.KEYCODE_BUTTON_B -> {
                        if (native.action == KeyEvent.ACTION_UP && !native.isCanceled) {
                            // A keyboard that's up owns cancel: B puts it away and leaves you in
                            // the field. Only the next B closes the popup (Android's Back rule).
                            val imeUp = ViewCompat.getRootWindowInsets(view)
                                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                            when {
                                interceptBack?.invoke() == true -> {}
                                backRegistry.handle() -> {}
                                imeUp       -> keyboard?.hide()
                                dismissible -> onDismiss()
                            }
                        }
                        true
                    }
                    KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> {
                        if (native.action == KeyEvent.ACTION_UP && !native.isCanceled) onOptions?.invoke()
                        true
                    }
                    else -> {
                        val pad = KeyEvent.isGamepadButton(native.keyCode)
                        if (pad && native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                            onPadButton?.invoke(native.keyCode)
                        }
                        pad
                    }
                }
            }
        ) {
            CompositionLocalProvider(LocalDialogBack provides backRegistry) { content() }
        }
    }
}

