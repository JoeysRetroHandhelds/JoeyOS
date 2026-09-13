package com.joeyos.app.ui.components

import com.joeyos.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joeyos.app.ui.theme.*

/**
 * The one popup look, used by every popup in the app so they all match: the same sheet, corners,
 * border and width, and a header with the title on the left and the buttons that work on the
 * right. It sits on [JoeyDialog], which handles focus and the controller: mark the item focus
 * should open on with `Modifier.initialFocus()`, or it opens on the first one.
 *
 * Lists (Recently Played, pickers, options) pass `padded = false` and fill the body with
 * [PopupRow]s or [GameListRow]s, which carry their own inset. Anything else (a message, a form)
 * keeps the default padding and ends with a row of [JoeyButton]s.
 */
@Composable
fun JoeyPopup(
    title: String,
    onDismiss: () -> Unit,
    hint: String = "A select  •  B cancel",
    wide: Boolean = false,
    padded: Boolean = true,
    dismissible: Boolean = true,
    interceptBack: (() -> Boolean)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val screenH = LocalConfiguration.current.screenHeightDp
    val shape = RoundedCornerShape(20.dp)
    JoeyDialog(
        onDismiss = onDismiss,
        dismissible = dismissible,
        interceptBack = interceptBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (wide) 0.8f else 0.55f)
                .widthIn(min = 320.dp)
                .heightIn(max = (screenH * 0.85f).dp)
                .clip(shape)
                .background(SheetBg)
                .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
                .padding(vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (hint.isNotEmpty()) {
                    Text(hint, fontSize = 9.sp, fontFamily = JoeyFont, color = TextFaint, maxLines = 1)
                }
            }
            Column(
                modifier = if (padded) Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 10.dp)
                    else Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(if (padded) 10.dp else 2.dp),
                content = content
            )
        }
    }
}

/**
 * A row in a popup list: one focus target, lit and ringed in the accent colour when focused. [isCurrent] marks
 * the chosen value in a picker with a tick (null for a plain action list, which has no tick column).
 * Sideways presses are cancelled: a full-width row has nothing beside it, and an unanswered one
 * would fall to a geometric search that jumps elsewhere (found in Chameleon).
 */
@Composable
fun PopupRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean? = null,
    detail: String? = null
) {
    PopupRowFrame(onClick, modifier) {
        if (isCurrent != null) {
            Box(Modifier.width(16.dp)) { if (isCurrent) JoeyIcon(R.drawable.ic_check, Accent, 16.dp) }
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontFamily = JoeyFont,
                color = if (isCurrent == true) Accent else TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, fontSize = 10.sp, fontFamily = JoeyFont, color = TextFaint, maxLines = 2)
            }
        }
    }
}

/**
 * What every popup list row shares, so they all behave and light up the same: one focus target,
 * the accent ring and lift when focused, sideways presses cancelled, and the chevron at the end.
 * [content] is what sits before the chevron — [PopupRow]'s tick and text, or [GameListRow]'s
 * number badge and title. Game rows are a little tighter ([verticalPadding]) as they carry two
 * lines of text.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PopupRowFrame(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 12.dp,
    spacing: Dp = 10.dp,
    content: @Composable RowScope.() -> Unit
) {
    val (source, focused) = rememberFocusState()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel }
            .padding(horizontal = 6.dp)
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .then(if (focused) Modifier.border(FocusWidth, FocusColor, shape) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        content()
        if (focused) JoeyIcon(R.drawable.ic_chevron_right, FocusColor, 18.dp)
    }
}

/** The line shown in a popup list while it loads or has nothing in it. */
@Composable
fun PopupNote(text: String) {
    Text(text, fontSize = 12.sp, fontFamily = JoeyFont, color = TextFaint,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
}
