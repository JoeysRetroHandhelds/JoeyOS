package com.joeyos.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joeyos.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Shared controller-first building blocks. Every one is a single focus target whose highlight
 * is its real focus (never a remembered index), per the Compose focus guidance.
 */

/**
 * The one highlight colour for "this is where you are", used by every focusable in the app.
 * A setting's current value is shown differently (a soft accent fill), so the two never collide.
 */
val FocusColor = Accent
val FocusWidth = 2.dp

/**
 * A row of choices with focus memory, per the TV navigation guidance ("focus should be
 * predictable"): coming into the row from above or below returns to the item you last used in
 * it, and the first time lands on [first] (the leftmost). Without it Android picks whichever item
 * sits nearest the one you came from, which in rows of different widths is rarely the one you
 * expect (found on device: Up from "−" landed on 42, not 32).
 */
fun Modifier.focusRow(first: FocusRequester): Modifier =
    this.focusRestorer(first).focusGroup()

/**
 * A one-shot "focus lands here next" hand-off, for when something opens or changes and focus has
 * to go to an element that may not exist yet: a popup's rows that are still loading, a lazy row
 * that is only composed at the next layout, the Cancel button a run is about to show.
 *
 * The element itself asks for focus ([landFocus]), from an effect that only starts once it is in
 * the tree ("Focus in Compose": request focus from an effect or an event, never before the target
 * is attached). So there is no frame wait, no retry, and no "not attached" error to swallow: if
 * the target isn't there yet, the request simply happens when it arrives. [arm] it in the same
 * event that makes the change (opening a tool, starting a run), and the next marked element to be
 * attached — or the one already there — takes focus once.
 */
@Stable
class FocusLanding(armed: Boolean = true) {
    internal var armedAt by mutableIntStateOf(if (armed) 1 else 0)
    internal var landedAt by mutableIntStateOf(0)
    val pending: Boolean get() = armedAt != landedAt
    /** Land again, on the next marked element that is attached (at once if one already is). */
    fun arm() { armedAt = maxOf(armedAt, landedAt) + 1 }
}

/** The landing a popup or page provides for its content ([initialFocus]). */
val LocalFocusLanding = staticCompositionLocalOf<FocusLanding?> { null }

/**
 * Marks where focus lands for [landing] (see [FocusLanding]). [enabled] picks which of several
 * candidates is the target (the row you came from, the current choice). Works in lazy lists: a
 * row that isn't composed yet takes focus when the list composes it.
 */
fun Modifier.landFocus(landing: FocusLanding?, enabled: Boolean = true): Modifier = composed {
    val requester = remember { FocusRequester() }
    if (landing != null && enabled && landing.pending) {
        val generation = landing.armedAt
        LaunchedEffect(landing, generation) {
            if (requester.requestFocus(FocusDirection.Enter)) landing.landedAt = generation
            // Refused (not focusable right now, or another window has focus): say so in the log,
            // since the symptom is only "nothing highlighted".
            else com.joeyos.app.AppLog.i("Controls", "A focus request was refused (landing $generation)")
        }
    }
    this.focusRequester(requester)
}

/** Where focus lands when the popup ([JoeyDialog]) or page ([JoeyPage]) around it opens. */
fun Modifier.initialFocus(enabled: Boolean = true): Modifier = composed {
    this.landFocus(LocalFocusLanding.current, enabled)
}

/** An interaction source plus whether it currently holds focus — the highlight for one element. */
@Composable
fun rememberFocusState(): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    return source to focused
}

/**
 * One choice in a row of choices (dock size, clock format, …). Soft accent fill when it's the
 * current value; the accent focus ring when it holds focus.
 *
 * [compact] is the pill form for rows of tabs and filters that flow (the second screen): fully
 * rounded and as wide as its label, where the normal chip is sized by its row.
 */
@Composable
fun OptionChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp,
    compact: Boolean = false
) {
    val (source, focused) = rememberFocusState()
    val shape = if (compact) RoundedCornerShape(50) else RoundedCornerShape(9.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(when {
                active  -> Accent.copy(alpha = 0.22f)
                focused -> Color.White.copy(alpha = 0.10f)
                else    -> Color.White.copy(alpha = 0.05f)
            })
            .border(
                if (focused) FocusWidth else 1.dp,
                when {
                    focused -> FocusColor
                    active  -> AccentSoft
                    else    -> Color.White.copy(alpha = 0.12f)
                },
                shape
            )
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .then(if (compact) Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                  else Modifier.padding(vertical = 11.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = fontSize, fontFamily = JoeyFont,
            color = when {
                active  -> Accent
                focused -> TextPrimary
                else    -> TextDim
            })
    }
}

/**
 * Makes a read-only row a focus stop, lit when focused. Android only scrolls a list to follow
 * focus, so content with nothing to press (award lists, the beaten table) needs a stop of its
 * own for the D-pad to reach it.
 */
fun Modifier.readOnlyFocus(shape: Shape = RoundedCornerShape(10.dp)): Modifier = composed {
    val (source, focused) = rememberFocusState()
    this
        .then(if (focused) Modifier.border(FocusWidth, FocusColor, shape) else Modifier)
        .focusable(interactionSource = source)
}

/**
 * Lets a text field inside a [JoeyDialog] claim B / Back while it's being typed in, so cancel
 * steps back one level (stop typing) before it closes the popup.
 */
class DialogBackRegistry {
    private val handlers = mutableListOf<() -> Boolean>()
    fun add(handler: () -> Boolean) { handlers += handler }
    fun remove(handler: () -> Boolean) { handlers -= handler }
    /** True when a registered handler consumed the press. */
    fun handle(): Boolean = handlers.asReversed().any { it() }
}

val LocalDialogBack = staticCompositionLocalOf<DialogBackRegistry?> { null }

/**
 * A text field that behaves on a controller the TV way (found on device: a plain field popped
 * the keyboard just by being landed on, swallowed B, and trapped the D-pad):
 *  - Landing on it only highlights it. A — or a tap — starts typing and opens the keyboard.
 *  - While typing, B / Back stops typing and leaves it highlighted; the keyboard's Done stops
 *    typing and moves on ([onDone], or the next item down).
 *  - While the keyboard is up the D-pad belongs to the keyboard, which is how you type without
 *    a touchscreen.
 */
@Composable
fun ControllerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onDone: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    var typing by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    // The highlight-only stop comes back when typing stops; this puts focus on it as it arrives.
    val backToBox = remember { FocusLanding(armed = false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val (boxSource, boxFocused) = rememberFocusState()
    val (fieldSource, fieldFocused) = rememberFocusState()
    val lit = boxFocused || fieldFocused

    fun stopTyping(moveOn: Boolean) {
        // Done moves on while the field still holds focus, so the move starts from here; B (or
        // a Done with nothing below) goes back to the field's own stop.
        val movedOn = moveOn && (if (onDone != null) { onDone(); true } else focusManager.moveFocus(FocusDirection.Down))
        if (!movedOn) backToBox.arm()
        keyboard?.hide()
        typing = false
    }

    val registry = LocalDialogBack.current
    val backHandler: () -> Boolean = remember { { if (typing) { stopTyping(moveOn = false); true } else false } }
    DisposableEffect(registry) {
        registry?.add(backHandler)
        onDispose { registry?.remove(backHandler) }
    }
    // On a page (not in a popup window) B / Back reaches the activity's back dispatcher; while
    // typing, this handler — registered after the page's own — takes it first.
    BackHandler(enabled = typing && registry == null) { stopTyping(moveOn = false) }
    // Tell the second screen, so a keyboard shown under it (the Thor's) can be seen and used.
    LaunchedEffect(typing) { com.joeyos.app.data.SecondScreenState.setTyping(typing) }
    DisposableEffect(Unit) { onDispose { com.joeyos.app.data.SecondScreenState.setTyping(false) } }
    // The field is always composed (only its canFocus follows typing), so it is attached when
    // this runs, after the recomposition that turned typing on.
    LaunchedEffect(typing) {
        if (typing) {
            fieldFocus.requestFocus()
            keyboard?.show()
        }
    }

    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = if (lit) 0.12f else 0.06f))
            .border(if (lit) FocusWidth else 1.dp, if (lit) FocusColor else Color.White.copy(alpha = 0.12f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                interactionSource = fieldSource,
                visualTransformation = if (isPassword && !showPassword)
                    PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { stopTyping(moveOn = true) }),
                textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp, fontFamily = JoeyFont),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(
                        if (typing) placeholder else "$placeholder  (A to type)",
                        fontSize = 13.sp, fontFamily = JoeyFont, color = TextFaint)
                    inner()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .focusProperties { canFocus = typing }
            )
            if (!typing) {
                // The focus stop while not typing. Pressing it (A or a tap) starts typing.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .landFocus(backToBox)
                        .clickable(interactionSource = boxSource, indication = null) { typing = true }
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Brings this whole block into view (a heading, a preview and the row of choices under them)
 * whenever focus is anywhere inside it. The list on its own only scrolls far enough to show the
 * focused control, so a tall heading above a first row stayed hidden (found on device:
 * Appearance's dock-size row hid "Dock icon size" and its preview). This asks for the block's
 * bounds with a BringIntoViewRequester, the Compose API for exactly this, so no scroll index
 * or frame wait is involved; the theme's BringIntoViewSpec still keeps its margin.
 */
fun Modifier.revealWholeOnFocus(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    this
        .bringIntoViewRequester(requester)
        .onFocusChanged { if (it.hasFocus) scope.launch { requester.bringIntoView() } }
}
