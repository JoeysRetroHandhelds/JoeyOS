package com.joeyos.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
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
 *  - It contains focus: the page is one focus group that cancels any move out of it ("Focus in
 *    Compose", focusProperties onExit), so the D-pad can't reach the home screen beneath. The
 *    home screen doesn't have to switch its dock off, and when the page closes the dock's own
 *    focusRestorer puts focus back on the icon you left.
 *  - Focus lands on the item marked `Modifier.initialFocus()` as soon as it's attached (see
 *    [FocusLanding]), so content that arrives after the page opens still gets it.
 *  - It swallows touches, so nothing underneath reacts.
 */
@Composable
fun JoeyPage(
    onClose: () -> Unit,
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

    // A page can be opened by a tap (the top bar's buttons), which leaves the window in touch
    // mode, where Compose won't focus a clickable: nothing inside would hold focus, so the page
    // couldn't contain it. A page is a controller screen first, so it asks for keyboard mode on
    // open; a page opened from the pad is already in it and this does nothing. Launched ahead of
    // the content's effects, so it's in place before the marked item asks for focus.
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }

    val landing = remember { FocusLanding() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } }
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
    ) {
        CompositionLocalProvider(LocalFocusLanding provides landing) { content() }
    }
}
