package com.joeyos.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
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
 * A setting's current value is shown differently (a soft amber fill), so the two never collide.
 */
val FocusColor = Amber
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

/** An interaction source plus whether it currently holds focus — the highlight for one element. */
@Composable
fun rememberFocusState(): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    return source to focused
}

/**
 * One choice in a row of choices (dock size, clock format, …). Soft amber fill when it's the
 * current value; the amber focus ring when it holds focus.
 */
@Composable
fun OptionChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp
) {
    val (source, focused) = rememberFocusState()
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(when {
                active  -> Amber.copy(alpha = 0.22f)
                focused -> Color.White.copy(alpha = 0.10f)
                else    -> Color.White.copy(alpha = 0.05f)
            })
            .border(
                if (focused) FocusWidth else 1.dp,
                when {
                    focused -> FocusColor
                    active  -> AmberSoft
                    else    -> Color.White.copy(alpha = 0.12f)
                },
                shape
            )
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = fontSize, fontFamily = JoeyFont,
            color = when {
                active  -> Amber
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
    val boxFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val (boxSource, boxFocused) = rememberFocusState()
    val (fieldSource, fieldFocused) = rememberFocusState()
    val lit = boxFocused || fieldFocused

    fun stopTyping(moveOn: Boolean) {
        keyboard?.hide()
        typing = false
        scope.launch {
            withFrameNanos { }
            runCatching { boxFocus.requestFocus() }
            if (moveOn) { if (onDone != null) onDone() else focusManager.moveFocus(FocusDirection.Down) }
        }
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
    LaunchedEffect(typing) {
        if (typing) {
            withFrameNanos { }
            runCatching { fieldFocus.requestFocus() }
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
                        .focusRequester(boxFocus)
                        .clickable(interactionSource = boxSource, indication = null) { typing = true }
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Keeps a page from being left with nothing focused. A page's first rows can arrive after it
 * opens (a scan of your ROM folders), and a run's Cancel button disappears when the run ends;
 * either way focus had nowhere to be and the controls did nothing (found on device: Generate
 * .m3u). Whenever [keys] change and nothing on the page holds focus, [target] gets it, with the
 * list scrolled to the top first so the row exists to take it.
 */
@Composable
fun Modifier.keepFocus(target: FocusRequester, listState: androidx.compose.foundation.lazy.LazyListState, vararg keys: Any?): Modifier {
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(*keys) {
        withFrameNanos { }
        if (!hasFocus) {
            runCatching { listState.scrollToItem(0) }
            withFrameNanos { }
            runCatching { target.requestFocus() }
        }
    }
    return this.onFocusChanged { hasFocus = it.hasFocus }
}

/**
 * For the first row of a page whose heading sits above it (a label, a preview): moving onto it
 * scrolls the whole list back to the top, so the heading shows. The margin scrolling in the theme
 * covers a line of text; this covers a tall preview too (found on device: Appearance's dock-size
 * row hid "Dock icon size" and its icon).
 */
fun Modifier.revealListTop(listState: androidx.compose.foundation.lazy.LazyListState): Modifier = composed {
    val scope = rememberCoroutineScope()
    onFocusChanged { if (it.hasFocus) scope.launch { withFrameNanos { }; listState.animateScrollToItem(0) } }
}
