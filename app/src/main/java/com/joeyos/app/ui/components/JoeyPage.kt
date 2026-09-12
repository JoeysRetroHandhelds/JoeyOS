package com.joeyos.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import com.joeyos.app.ui.controls.Control
import com.joeyos.app.ui.controls.ControlBus

/**
 * A full-screen page over the home screen (Settings, the App Drawer), drawn in the main window.
 *
 * Pages are not Dialog windows: Android keeps a dialog window inside the status and navigation
 * bars, which left strips of the home screen showing (found on device), and the dialogs guidance
 * is for small, focused choices, not full screens. Drawn in the activity, a page is edge to edge
 * like the home screen and gets the MainActivity input layer (A = confirm, B = Back).
 *
 * What a page does that a window would have done for it:
 *  - B / Back closes it (a text field being typed in registers after, so it's asked first).
 *  - While open it takes the app's own buttons (Start, L1/R1, …) via the ControlBus stack.
 *  - On open it asks for keyboard input mode and lands focus on [initialFocus] (or the first
 *    item). The caller keeps the home screen out of focus while a page is open, and puts focus
 *    back on the dock when it closes.
 *  - It swallows touches, so nothing underneath reacts.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun JoeyPage(
    onClose: () -> Unit,
    initialFocus: FocusRequester? = null,
    /** Re-lands focus when this changes (content that arrives after the page opens). */
    focusKey: Any? = Unit,
    /** The page's own buttons; return true when handled. Unhandled ones do nothing. */
    onControl: (Control) -> Boolean = { false },
    content: @Composable () -> Unit
) {
    BackHandler(onBack = onClose)

    val currentOnControl by rememberUpdatedState(onControl)
    DisposableEffect(Unit) {
        val handler: (Control) -> Boolean = { currentOnControl(it); true }
        ControlBus.push(handler)
        onDispose { ControlBus.pop(handler) }
    }

    val inputMode = LocalInputModeManager.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(focusKey) {
        withFrameNanos { }
        inputMode.requestInputMode(InputMode.Keyboard)
        val landed = initialFocus != null && runCatching { initialFocus.requestFocus() }.isSuccess
        if (!landed) focusManager.moveFocus(FocusDirection.Next)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } }
            .focusGroup()
    ) {
        content()
    }
}
