package com.joeyos.app.ui.components

import com.joeyos.app.R
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joeyos.app.data.InstalledApp
import com.joeyos.app.data.DisplayTargets
import com.joeyos.app.data.SecondScreenPrefs
import com.joeyos.app.ui.controls.Control
import androidx.activity.compose.BackHandler
import com.joeyos.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * All installed apps, full screen. A page (JoeyPage), so focus stays inside it and returns to
 * the dock icon you left when it closes. Focus opens on the first app; the search
 * box is a normal stop above the grid (Up from the top row) and never pops the keyboard on open.
 * Start on an app opens its options (App Info), as long-press does by touch.
 */
@Composable
fun AppDrawer(
    installedApps: List<InstalledApp>,
    onLaunch: (String) -> Unit,
    onDismiss: () -> Unit,
    isInDock: (String) -> Boolean = { false },
    onSetInDock: (String, Boolean) -> Unit = { _, _ -> }
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, installedApps) {
        if (query.isBlank()) installedApps
        else installedApps.filter { it.label.contains(query, ignoreCase = true) }
    }
    var focusedApp by remember { mutableStateOf<InstalledApp?>(null) }
    var contextApp by remember { mutableStateOf<InstalledApp?>(null) }
    val firstApp = remember { FocusRequester() }
    val grid = remember { FocusRequester() }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    val gridState = rememberLazyGridState()
    var typing by remember { mutableStateOf(false) }
    LaunchedEffect(typing) { com.joeyos.app.data.SecondScreenState.setTyping(typing) }
    DisposableEffect(Unit) { onDispose { com.joeyos.app.data.SecondScreenState.setTyping(false) } }
    val fieldFocus = remember { FocusRequester() }
    // The search box's highlight-only stop comes back when typing stops; this lands on it.
    val backToBox = remember { FocusLanding(armed = false) }
    // A new search starts at the top of its results, so the first result is the one on screen.
    LaunchedEffect(query) { gridState.scrollToItem(0) }

    /**
     * Leave typing: hide the keyboard and land on the search box, or (the keyboard's Done) go
     * down to the results. Done moves while the field still holds focus, so the move starts from
     * the field and goes through the same Down route as the box (below).
     */
    fun stopTyping(toResults: Boolean) {
        val moved = toResults && filtered.isNotEmpty() && grid.requestFocus(FocusDirection.Down)
        if (!moved) backToBox.arm()
        keyboard?.hide()
        typing = false
    }

    JoeyPage(
        onClose      = onDismiss,
        onControl    = { control ->
            if (control == Control.Options) focusedApp?.let { contextApp = it }
            true
        }
    ) {
        // While typing, B / Back stops typing first (registered after the page's own handler).
        BackHandler(enabled = typing) { stopTyping(toResults = false) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SheetBg)
                .systemBarsPadding()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("All Apps", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("A open  •  Start options  •  B close", fontSize = 9.sp,
                        fontFamily = JoeyFont, color = TextFaint)
                    // Touch: back to the home screen (B does the same on a controller).
                    CircleIconButton(com.joeyos.app.R.drawable.ic_close, onClick = onDismiss)
                }
            }

            // Search box. Two states, the TV way: landing on it only highlights it (the overlay
            // is the focus stop); A — or a tap — starts typing and opens the keyboard. While
            // typing, B or Back leaves typing, and the keyboard's Done goes to results. The D-pad
            // belongs to the on-screen keyboard while it's up (that's how you type without touch).
            // (Found on device: a plain text field popped the keyboard just by being landed on,
            // and swallowed B and Down.)
            val boxInteraction = remember { MutableInteractionSource() }
            val boxFocused by boxInteraction.collectIsFocusedAsState()
            val fieldInteraction = remember { MutableInteractionSource() }
            val fieldFocused by fieldInteraction.collectIsFocusedAsState()
            val lit = boxFocused || fieldFocused
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = if (lit) 0.12f else 0.07f))
                        .border(if (lit) 2.dp else 1.dp,
                            if (lit) Accent else Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    JoeyIcon(R.drawable.ic_search, TextFaint, 18.dp, Modifier.padding(end = 8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        interactionSource = fieldInteraction,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { stopTyping(toResults = true) }),
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontFamily = JoeyFont
                        ),
                        decorationBox = { inner ->
                            if (query.isEmpty()) Text(
                                if (typing) "Type to search…" else "Search apps…  (A to type)",
                                fontSize = 14.sp, fontFamily = JoeyFont, color = TextFaint)
                            inner()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(fieldFocus)
                            .focusProperties { canFocus = typing }

                    )
                }
                if (!typing) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .landFocus(backToBox)
                            // Down from the full-width box goes into the grid as a whole: the app
                            // you came up from, else the first result (focusProperties +
                            // focusRestorer, "Focus in Compose"). Left to itself, focus search
                            // picks the app nearest the box's centre (found on device).
                            .focusProperties { down = grid }
                            .clickable(interactionSource = boxInteraction, indication = null) { typing = true }
                    )
                }
            }
            // The field is always composed (only its canFocus follows typing), so it's attached
            // when this runs, after the recomposition that turned typing on.
            LaunchedEffect(typing) {
                if (typing) {
                    fieldFocus.requestFocus()
                    keyboard?.show()
                }
            }

            // App grid. Keyed by package, so focus follows an app if the list changes; the grid
            // brings the focused app into view itself as the D-pad walks it. One focus group
            // that remembers the app you left it from; the first app is where it starts, and
            // where the drawer opens (it takes focus as soon as the grid composes it).
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(grid)
                    .focusRestorer(firstApp)
                    .focusGroup()
            ) {
                itemsIndexed(filtered, key = { _, app -> app.packageName }) { i, app ->
                    AppGridItem(
                        app         = app,
                        onClick     = { onLaunch(app.packageName) },
                        onLongClick = { contextApp = app },
                        onFocused   = { focusedApp = app },
                        modifier    = if (i == 0) Modifier.focusRequester(firstApp).initialFocus() else Modifier
                    )
                }
            }
        }
    }

    // Options for one app (Start, or long-press by touch)
    contextApp?.let { app ->
        JoeyPopup(title = app.label, onDismiss = { contextApp = null }, padded = false) {
                PopupRow("App Info", onClick = {
                    contextApp = null
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", app.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                })
                val inDock = isInDock(app.packageName)
                PopupRow(if (inDock) "Remove from dock" else "Add to dock", onClick = {
                    onSetInDock(app.packageName, !inDock)
                    Toast.makeText(context,
                        if (inDock) "${app.label} removed from the dock" else "${app.label} added to the dock",
                        Toast.LENGTH_SHORT).show()
                    contextApp = null
                })
                // Dual-screen handhelds: this app can open on the other screen instead.
                if (remember { DisplayTargets.hasSecondScreen(context) }) {
                    val onOther = app.packageName in SecondScreenPrefs.otherScreenApps(context)
                    PopupRow("Open on the other screen", isCurrent = onOther, onClick = {
                        SecondScreenPrefs.setOnOtherScreen(context, app.packageName, !onOther)
                        Toast.makeText(context,
                            if (onOther) "${app.label} opens on this screen" else "${app.label} opens on the other screen",
                            Toast.LENGTH_SHORT).show()
                        contextApp = null
                    })
                }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppGridItem(
    app: InstalledApp,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onFocused: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val isSelected by interaction.collectIsFocusedAsState()
    // Cached and drawn at the tile's size; shared with the dock and the second screen's grid.
    val icon by rememberAppIcon(app.packageName, 52.dp)

    Column(
        modifier = modifier
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clip(RoundedCornerShape(12.dp))
            .then(if (isSelected) Modifier.background(Color.White.copy(alpha = 0.12f)) else Modifier)
            .border(if (isSelected) 2.dp else 1.dp, if (isSelected) Accent else Color.Transparent, RoundedCornerShape(12.dp))
            // One clickable node = one focus target.
            .combinedClickable(interactionSource = interaction, indication = null,
                onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) {
            androidx.compose.foundation.Image(
                bitmap = icon!!,
                contentDescription = app.label,
                modifier = Modifier.size(52.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Text(app.label.take(2).uppercase(), fontSize = 16.sp, color = TextDim,
                    fontFamily = JoeyFont)
            }
        }
        Text(
            app.label,
            fontSize = 10.sp,
            color = TextDim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 13.sp
        )
    }
}
