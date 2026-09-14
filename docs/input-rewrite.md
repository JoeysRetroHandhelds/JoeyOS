# Controls rebuild

JoeyOS's controls were a hand-rolled system: MainActivity swallowed every D-pad press, each
screen counted its own "selected index", and one long `when` in HomeScreen decided what every
button meant depending on which overlay was open. That is the opposite of the Android guidance,
and it is the root of most controller problems (remote Select/Back dead, top bar and text fields
unreachable, highlight stranded when the dock re-sorts).

This is the from-scratch replacement, following the same approach as Chameleon's input, windows
and D-pad rewrites. The old code is on the `legacy-input-reference` branch.

Sources: developer.android.com/training/tv/get-started/controllers, .../navigation,
Compose "Focus in Compose" (traversal order, focus behaviour, focusRestorer, focusGroup,
focusProperties, FocusRequester), the Android dialogs guide, and the game-controller input guide.

## Rules

- Best practice is the baseline. Check the Android docs before deciding, and cite them.
- Don't carry old workarounds across. Add a device fix back only when the device proves it's
  needed.
- Small steps, each tested on a device before the next.
- No hold-A. One button, one job. (Chameleon: a hold on confirm caused the "opened a menu and
  went past it" bug.)
- No timers in focus code. No `delay`, `withFrameNanos`, `postDelayed` or retry before a focus
  request, and no `runCatching` around `requestFocus` to hide one that came too early. If a
  request can come before its target exists, restructure so it can't (below).

## Focus rules

- **Request only targets that are attached.** A request is made from an event, or from an
  effect in the same composition as its target (so the target is in the tree when it runs).
  When the target may not exist yet — rows still loading, a lazy row the list hasn't composed,
  a Cancel button a run is about to show — use a `FocusLanding`: arm it in the event that makes
  the change, mark the target with `Modifier.landFocus(landing)`, and the target asks for focus
  itself as it's attached. If it has to be scrolled in first, scroll; it then asks by itself.
- **Popups and pages mark where focus opens** with `Modifier.initialFocus()` (a picker's
  current row, a list's first row). A popup with no mark opens on its first item. A list that
  opens on a row further down starts scrolled to it (`initialFirstVisibleItemIndex`), so the row
  is composed.
- **Containers remember, the app doesn't.** Rows, the App Drawer grid and the Settings panel are
  focus groups with `focusRestorer(first)`: coming back into one lands on the item you left, else
  its first item. To go back to a container, request the container.
- **Except the dock**, a plain `focusGroup` with no restorer: the home screen names the icon to
  return to (the one reported focused) and that icon requests focus. A restorer steps in on every
  entry into its group, including a request on a child, so it sent those requests to its fallback
  (found on device: always the first emulator). Don't mix a restorer with explicit child requests.
- **Explicit steps use `focusProperties`**, not key interception: Down from the full-width
  search box is `down = grid`, Down from the Settings tabs is `down = panel`; a full-width popup
  row cancels left/right.
- **Pages contain focus**: a page is one focus group whose `onExit` cancels, so the D-pad can't
  leave it. Nothing underneath is switched off.
- **When a focused control disappears, move focus in the same event that removes it** (Cancel
  at a run's end, Undo once used, a tab's panel on L1/R1).
- **One list replacing another keeps each list's own state**, so going back finds the row you
  came from on screen and composed (Tools hub, the romhack levels).
- **Keyboard input mode** is asked for only where the window may be in touch mode when focus
  has to show: a new Dialog window (always starts in touch mode), a page (may be opened by a
  tap), the home screen and the first-run screen on start (a window starts in touch mode).

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
4. **Popups are real Dialog windows** (`JoeyDialog`): focus is contained and restored, and on
   open they switch to keyboard input mode so focus shows at once. The dialog's window gets keys
   before the Activity, so it does its own translation: A is a centre press on release (only a
   release whose press it saw), B goes through the same exit route as Back (typing, then the
   keyboard, then close if `dismissible`), and other pad buttons are swallowed on both edges.
5. **Full-screen pages are not dialogs** (`JoeyPage`: Settings, the App Drawer). They're drawn in
   the main window, edge to edge like the home screen. A page closes on Back, pushes its own
   `ControlBus` handler while open, contains focus (its group cancels exits), and lands focus on
   its marked item. When it closes, the home screen asks the icon you were on for focus (it's
   tracked by package as the dock reports focus).

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

- A dialog's `view.requestFocus()` only focuses the window, not its first item: request the
  dialog body's focus group (it enters its first item) or mark an item.
- A new Dialog window starts in touch mode, where Compose won't focus a clickable: nothing looked
  selected until a second press. Ask for keyboard mode on open.
- A plain text field pops the keyboard when focus lands on it and swallows B/Down. Use
  `ControllerTextField` (highlight first, A to type).
- While the soft keyboard is up, the D-pad goes to the keyboard. B leaves typing.
- A full-width box above a grid: Down lands on the item nearest its centre. Send it into the
  grid with `focusProperties { down = … }`; the grid's focusRestorer picks the item.
- A Dialog window is held inside the status / navigation bars; making it cover them
  (decorFitsSystemWindows=false, FLAG_LAYOUT_NO_LIMITS) made it worse. Full-screen content is a
  page in the main window instead.

## Decisions

- B / Back on the home screen opens the App Drawer. This departs from the TV guidance (Back must
  not open things and should do nothing at home) on purpose: it's the familiar handheld shortcut.
