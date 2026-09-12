package com.joeyos.app.ui.controls

import android.view.KeyEvent

/**
 * The controls the app speaks, and the one place hardware becomes them.
 *
 * Built on the Android / Compose best practice (developer.android.com/training/tv/get-started/
 * controllers and .../navigation, "Focus in Compose"): the focus system moves focus, `clickable`
 * answers confirm, `BackHandler` answers cancel, and a single normalisation layer in MainActivity
 * cleans up the pad before Compose sees it. It replaces a hand-rolled, index-counting input
 * system; the old one is preserved on the `legacy-input-reference` branch, and the plan is in
 * docs/input-rewrite.md.
 *
 * Two kinds of button, and only two:
 *
 *  - **Navigation, confirm and cancel** are the platform's. The D-pad moves focus on its own;
 *    confirm is DPAD centre and cancel is Back, which every `clickable` and `BackHandler`
 *    already answers. A pad's A and B are the same intentions wearing keycodes Compose does not
 *    recognise, so [translate] turns them into the ones it does.
 *
 *  - **Everything else is a [Control] intent**, delivered by [ControlBus] to one owner.
 */
enum class Control {
    /** Start (or a remote's Menu). Opens Settings. */
    Options,

    /** X. Launch the focused emulator's most recent game. */
    QuickLaunch,

    /** Y. Recently Played for the focused emulator, or the favourite picker. */
    Recent,

    /** L1 / R1. One step along the dock. */
    StepPrev,
    StepNext,

    /** L2 / R2. A page along the dock. */
    PagePrev,
    PageNext,
}

object Controls {

    /** The intent a keycode carries, or null when it is navigation, confirm, cancel or unknown. */
    fun intentFor(keyCode: Int): Control? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_MENU        -> Control.Options
        KeyEvent.KEYCODE_BUTTON_X    -> Control.QuickLaunch
        KeyEvent.KEYCODE_BUTTON_Y    -> Control.Recent
        KeyEvent.KEYCODE_BUTTON_L1   -> Control.StepPrev
        KeyEvent.KEYCODE_BUTTON_R1   -> Control.StepNext
        KeyEvent.KEYCODE_BUTTON_L2   -> Control.PagePrev
        KeyEvent.KEYCODE_BUTTON_R2   -> Control.PageNext
        else -> null
    }

    /**
     * The platform keycode a pad button stands for, or null to pass the key through untouched.
     *
     * Compose's `clickable` answers DPAD centre and Enter, never a pad's BUTTON_A, and
     * `BackHandler` answers Back, never BUTTON_B. Relying on Android's own fallback for these
     * is unreliable on handhelds (learned in Chameleon), so they are translated explicitly.
     * The TV controllers guide lists BUTTON_SELECT as a selection key too.
     */
    fun translate(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_SELECT -> KeyEvent.KEYCODE_DPAD_CENTER
        KeyEvent.KEYCODE_BUTTON_B      -> KeyEvent.KEYCODE_BACK
        else -> null
    }
}

/**
 * The owner of control intents. One handler is in charge at a time — the top of a stack: the
 * home screen sets the base, and a page opened over it (Settings, the App Drawer) pushes its own
 * while it's open, so a button is never handled in two places that drift apart.
 */
object ControlBus {
    private val handlers = ArrayDeque<(Control) -> Boolean>()

    /** Sets the base handler (the home screen), or clears it with null. */
    fun setHandler(handler: ((Control) -> Boolean)?) {
        handlers.clear()
        if (handler != null) handlers.addLast(handler)
    }

    /** A page takes the buttons while it's open. */
    fun push(handler: (Control) -> Boolean) { handlers.addLast(handler) }
    fun pop(handler: (Control) -> Boolean) { handlers.remove(handler) }

    /** Offers an intent to the handler in charge. True when it was consumed. */
    fun dispatch(control: Control): Boolean = handlers.lastOrNull()?.invoke(control) ?: false
}
