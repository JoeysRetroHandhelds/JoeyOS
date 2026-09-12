# Controls rebuild

JoeyOS's controls were a hand-rolled system: MainActivity swallowed every D-pad press, each
screen counted its own "selected index", and one long `when` in HomeScreen decided what every
button meant depending on which overlay was open. That is the opposite of the Android guidance,
and it is the root of most controller problems (remote Select/Back dead, top bar and text fields
unreachable, highlight stranded when the dock re-sorts).

This is the from-scratch replacement, following the same approach as Chameleon's input, windows
and D-pad rewrites. The old code is on the `legacy-input-reference` branch.

Sources: developer.android.com/training/tv/get-started/controllers, .../navigation,
Compose "Focus in Compose", and the Android dialogs guide.

## Rules

- Best practice is the baseline. Check the Android docs before deciding, and cite them.
- Don't carry old workarounds across. Add a device fix back only when the device proves it's
  needed.
- Small steps, each tested on a device before the next.
- No hold-A. One button, one job. (Chameleon: a hold on confirm caused the "opened a menu and
  went past it" bug.)

## Architecture

1. **MainActivity normalises hardware, nothing more** (`ui/controls/Controls.kt`).
   A / Select become DPAD centre (sent as a press on release), B becomes Back. Start, Menu, X,
   Y, L1/R1, L2/R2 become one `Control` intent on the `ControlBus`, both key edges consumed so
   Android never synthesises a fallback. The D-pad and a remote's centre and Back pass through.
   Unknown pad buttons are written to the crash log once, so a new device names itself.
2. **Navigation is the Compose focus system.** `clickable` is the focus target (one per
   element); the highlight is real focus, not an index. Lists are keyed by identity so focus
   follows the item when the order changes.
3. **One owner for intents**: the screen in charge sets the single `ControlBus` handler.
4. **Popups are real Dialog windows** (`JoeyDialog`): focus is contained and restored, Back
   closes them, and on open they switch to keyboard input mode so focus shows at once. The
   dialog's window gets keys before the Activity, so it does its own A/B translation.
5. **Full-screen pages are not dialogs** (`JoeyPage`: Settings, the App Drawer). They're drawn in
   the main window, edge to edge like the home screen. A page closes on Back, pushes its own
   `ControlBus` handler while open, lands focus on open, and the home screen keeps the dock out of
   focus while it's up and refocuses the dock icon when it closes.

## Waves (one screen at a time, fully)

- [x] Home dock, update popup, button layer, controller-reconnect restart fix.
- [x] Recently Played popup
- [x] Favourite picker popup (opens on the current favourite)
- [x] App Drawer (Start = App Info; search is highlight-first, A to type, B to stop)
- [x] Settings: Appearance, Emulators, Achievements (`ControllerTextField` for logins;
      read-only rows are focus stops so the D-pad reaches the bottom)
- [x] Remove the legacy path (`legacyInput`, `ControllerEvent`, the old collector).
- [x] First-run screen (focus starts on the next step to do; Back does nothing)

## Device findings

- A dialog's `view.requestFocus()` only focuses the window, not its first item: name the
  first item with a FocusRequester (fallback: `moveFocus(Next)`).
- A plain text field pops the keyboard when focus lands on it and swallows B/Down. Use
  `ControllerTextField` (highlight first, A to type).
- While the soft keyboard is up, the D-pad goes to the keyboard. B leaves typing.
- A full-width box above a grid: Down lands on the item nearest its centre. Send it to the
  first item explicitly.
- A Dialog window is held inside the status / navigation bars; making it cover them
  (decorFitsSystemWindows=false, FLAG_LAYOUT_NO_LIMITS) made it worse. Full-screen content is a
  page in the main window instead.

## Decisions

- B / Back on the home screen opens the App Drawer. This departs from the TV guidance (Back must
  not open things and should do nothing at home) on purpose: it's the familiar handheld shortcut.
