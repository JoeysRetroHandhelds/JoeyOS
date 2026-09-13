package com.joeyos.app.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
 * Focus on open: the item marked `Modifier.initialFocus()` takes it (a picker's current choice, a
 * list's first row); with no mark, focus enters the popup at its first item. Either way the
 * request is made once the target is attached (see [FocusLanding]), so rows that load after the
 * popup opens take focus when they arrive.
 *
 * The dialog's window gets keys before MainActivity does, so the pad normalisation is done here
 * too (the game-controller guide: handle the buttons you use and consume both edges): A / Select
 * press the focused item, B takes the same exit route as Back, and every other pad button is
 * swallowed so Android can't synthesise a fallback (Start's fallback is a confirm).
 */
@Composable
fun JoeyDialog(
    onDismiss: () -> Unit,
    /** False for a popup that must be answered (the update prompt): Back, B and a tap outside do nothing. */
    dismissible: Boolean = true,
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
    val ime = remember { DialogIme() }

    /**
     * The one way out, for Back, B and a tap outside alike (the dialogs guidance routes every
     * dismissal through onDismissRequest): a field being typed in steps back first, then a
     * keyboard that's up is put away (Android's Back rule), and only then does the popup close —
     * if it may.
     */
    fun requestClose() {
        when {
            interceptBack?.invoke() == true -> {}
            backRegistry.handle()           -> {}
            ime.isUp()                      -> ime.hide()
            dismissible                     -> onDismiss()
        }
    }

    Dialog(
        onDismissRequest = ::requestClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Back always reaches requestClose, so a field in a must-answer popup still gets it;
            // requestClose is what refuses to close.
            dismissOnBackPress = true,
            dismissOnClickOutside = dismissible
        )
    ) {
        val view = LocalView.current
        // A dialog is its own window with its own bar state: without this, opening a popup
        // brings the status and navigation bars back over the full-screen home screen.
        SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
                androidx.core.view.WindowInsetsControllerCompat(window, view).apply {
                    systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        val keyboard = LocalSoftwareKeyboardController.current
        SideEffect {
            ime.isUp = { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            ime.hide = { keyboard?.hide() }
        }

        // A new dialog window starts in touch mode, where Compose won't focus a clickable, so
        // nothing looked selected until a second press (found on device). A popup is opened from
        // the pad but its window never saw the key that left touch mode in the window beneath,
        // so it asks for keyboard mode itself. Launched ahead of the content's effects, so it's
        // in place before the first item asks for focus.
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }

        val landing = remember { FocusLanding() }
        val body = remember { FocusRequester() }
        // Only a release whose press this window saw counts, as in MainActivity: a press that
        // began before the popup opened (A held as an update prompt appears) mustn't answer it.
        var aDownTime by remember { mutableLongStateOf(-1L) }
        Box(
            modifier = Modifier
                .focusRequester(body)
                .focusGroup()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    when (native.keyCode) {
                        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT -> {
                            // A becomes a DPAD centre tap so the focused item's clickable answers
                            // it, sent on release like MainActivity's: a hold can never turn into
                            // a long-press (one button, one job). Posted so it isn't dispatched
                            // from inside this dispatch.
                            if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) aDownTime = native.downTime
                            if (native.action == KeyEvent.ACTION_UP && !native.isCanceled && native.downTime == aDownTime) {
                                aDownTime = -1L
                                view.post {
                                    view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                                    view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                                }
                            }
                            true
                        }
                        KeyEvent.KEYCODE_BUTTON_B -> {
                            // B is Back: the same exit route, not a second one.
                            if (native.action == KeyEvent.ACTION_UP && !native.isCanceled) requestClose()
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
            CompositionLocalProvider(LocalDialogBack provides backRegistry, LocalFocusLanding provides landing) {
                content()
            }
        }
        // Nothing marked: enter the popup at its first item. Composed after the content, so a
        // marked item that's already attached asks first; the popup body is always attached here.
        // (view.requestFocus() alone only focuses the window, not an item in it — found on device.)
        LaunchedEffect(Unit) {
            if (landing.pending) body.requestFocus(FocusDirection.Enter)
        }
    }
}

/** Lets [JoeyDialog]'s exit route, which lives outside the dialog window, reach that window's keyboard. */
private class DialogIme {
    var isUp: () -> Boolean = { false }
    var hide: () -> Unit = {}
}
