package com.joeyos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joeyos.app.ui.theme.*

/**
 * The shared controls every screen is built from, so the app looks and behaves the same
 * everywhere: one section label, one button, one card row. Popups have their own shell in
 * Popup.kt; focus helpers (OptionChip, ControllerTextField, focusRow) are in Focusables.kt.
 * Add to these rather than styling a one-off on a screen.
 */

/**
 * An icon from Material Symbols (Rounded), res/drawable/ic_*.xml, the set JoeyOS uses instead of text symbols like ✓ or
 * ▶, which Inter doesn't have: vector, so crisp at any size, tinted exactly, one stroke weight.
 */
@Composable
fun JoeyIcon(@androidx.annotation.DrawableRes icon: Int, color: Color, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(icon), contentDescription = null, tint = color,
        modifier = modifier.size(size))
}

/** The small grey heading above a group of settings or a tool's section ("SYSTEM", "NAME"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), fontSize = 10.sp, fontFamily = JoeyFont, fontWeight = FontWeight.Bold,
        color = TextFaint, letterSpacing = 1.sp, modifier = modifier)
}

/**
 * The one button. Plain by default (Refresh, Cancel, Later); [primary] is the amber one for the
 * main action on a screen (Connect, Get started). Disabled buttons are greyed and not a focus stop.
 */
@Composable
fun JoeyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    fontSize: TextUnit = 13.sp,
    @androidx.annotation.DrawableRes icon: Int? = null
) {
    val (source, focused) = rememberFocusState()
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(when {
                !enabled           -> Color.White.copy(alpha = 0.04f)
                primary && focused -> Accent.copy(alpha = 0.30f)
                primary            -> Accent.copy(alpha = 0.16f)
                focused            -> Accent.copy(alpha = 0.22f)
                else               -> Color.White.copy(alpha = 0.06f)
            })
            .border(if (focused) FocusWidth else 1.dp, when {
                focused            -> FocusColor
                !enabled           -> Color.White.copy(alpha = 0.10f)
                primary            -> AccentSoft
                else               -> Color.White.copy(alpha = 0.14f)
            }, shape)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        val color = when {
            !enabled             -> TextFaint
            primary || focused   -> Accent
            else                 -> TextPrimary
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            icon?.let { JoeyIcon(it, color, (fontSize.value + 3).dp) }
            Text(label, fontSize = fontSize, fontFamily = JoeyFont, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

/**
 * A full-width card in a list (a tool, a setting with a tick box, a console's emulator): one
 * focus target, lit and ringed amber when focused. Sideways presses are cancelled, since a
 * full-width row has nothing beside it (an unanswered one jumps elsewhere, found in Chameleon).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun CardRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(focused: Boolean) -> Unit
) {
    val (source, focused) = rememberFocusState()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel }
            .clip(shape)
            .background(Color.White.copy(alpha = if (focused) 0.10f else 0.04f))
            .border(if (focused) FocusWidth else 1.dp, if (focused) FocusColor else Color.White.copy(alpha = 0.09f), shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) { content(focused) }
}

/** The title and grey detail line inside a [CardRow]. */
@Composable
fun RowScope.CardText(title: String, detail: String?, focused: Boolean) {
    Column(Modifier.weight(1f)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (focused) Accent else TextPrimary)
        if (!detail.isNullOrEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(detail, fontSize = 10.sp, fontFamily = JoeyFont, color = TextFaint)
        }
    }
}

/** Colours for a result: done / fine, needs a look, and missing or failed. */
val VerifiedColor = Color(0xFF34D399)
val WarnColor = Color(0xFFFBBF24)
val MissingColor = Color(0xFFF87171)

/** A one-line result or progress message ("Patched ROM written…", "Couldn't load…"). */
@Composable
fun StatusLine(text: String, color: Color, bold: Boolean = false) {
    Text(text, fontSize = 12.sp, fontFamily = JoeyFont, color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
}

/** A labelled row of [OptionChip]s for one setting. */
@Composable
fun <T> ChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    current: T,
    onChange: (T) -> Unit,
    fontSize: TextUnit = 11.sp
) {
    Column {
        Spacer(Modifier.height(4.dp))
        SectionLabel(label)
        Spacer(Modifier.height(8.dp))
        val first = remember { FocusRequester() }
        Row(modifier = Modifier.fillMaxWidth().focusRow(first), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, (value, text) ->
                OptionChip(text, current == value, { onChange(value) },
                    Modifier.weight(1f).then(if (i == 0) Modifier.focusRequester(first) else Modifier), fontSize)
            }
        }
    }
}
