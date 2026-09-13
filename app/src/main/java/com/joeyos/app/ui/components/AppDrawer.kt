package com.joeyos.app.ui.components

import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.ui.focus.FocusRequester
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
 * All installed apps, full screen. A real Dialog window (JoeyDialog), so focus stays inside it
 * and returns to the dock icon you left when it closes. Focus opens on the first app; the search
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
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    val gridState = rememberLazyGridState()
    var typing by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val boxFocus = remember { FocusRequester() }
    /**
     * Down from the full-width search box goes to the first result. Left to itself, focus search
     * picks the app nearest the box's centre (found on device), so this one step is explicit.
     * The grid is scrolled to the top first, so the first result exists to take focus.
     */
    fun goToFirstResult() {
        if (filtered.isEmpty()) return
        scope.launch {
            gridState.scrollToItem(0)
            withFrameNanos { }
            runCatching { firstApp.requestFocus() }
        }
    }
    /** Leave typing: hide the keyboard and land on the search box, or on the first result. */
    fun stopTyping(toResults: Boolean) {
        keyboard?.hide()
        typing = false
        if (toResults && filtered.isNotEmpty()) goToFirstResult()
        else scope.launch {
            withFrameNanos { }
            runCatching { boxFocus.requestFocus() }
        }
    }

    JoeyPage(
        onClose      = onDismiss,
        focusKey     = installedApps.isNotEmpty(),
        initialFocus = if (filtered.isNotEmpty()) firstApp else null,
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
                Text("A open  •  Start options  •  B close", fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace, color = TextFaint)
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
                            if (lit) Amber else Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔍 ", fontSize = 13.sp, color = TextFaint)
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
                            fontFamily = FontFamily.Monospace
                        ),
                        decorationBox = { inner ->
                            if (query.isEmpty()) Text(
                                if (typing) "Type to search…" else "Search apps…  (A to type)",
                                fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
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
                            .focusRequester(boxFocus)
                            .onPreviewKeyEvent { e ->
                                if (e.key == Key.DirectionDown && filtered.isNotEmpty()) {
                                    if (e.type == KeyEventType.KeyDown) goToFirstResult()
                                    true
                                } else false
                            }
                            .clickable(interactionSource = boxInteraction, indication = null) { typing = true }
                    )
                }
            }
            LaunchedEffect(typing) {
                if (typing) {
                    withFrameNanos { }
                    runCatching { fieldFocus.requestFocus() }
                    keyboard?.show()
                }
            }

            // App grid. Keyed by package, so focus follows an app if the list changes; the grid
            // brings the focused app into view itself as the D-pad walks it.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(filtered, key = { _, app -> app.packageName }) { i, app ->
                    AppGridItem(
                        app         = app,
                        onClick     = { onLaunch(app.packageName) },
                        onLongClick = { contextApp = app },
                        onFocused   = { focusedApp = app },
                        modifier    = if (i == 0) Modifier.focusRequester(firstApp) else Modifier
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
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(app.packageName)
                val w = drawable.intrinsicWidth.coerceIn(1, 256)
                val h = drawable.intrinsicHeight.coerceIn(1, 256)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                drawable.setBounds(0, 0, w, h)
                drawable.draw(canvas)
                bmp.asImageBitmap()
            } catch (e: Exception) { null }
        }
    }

    Column(
        modifier = modifier
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clip(RoundedCornerShape(12.dp))
            .then(if (isSelected) Modifier.background(Color.White.copy(alpha = 0.12f)) else Modifier)
            .border(if (isSelected) 2.dp else 1.dp, if (isSelected) Amber else Color.Transparent, RoundedCornerShape(12.dp))
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
                    fontFamily = FontFamily.Monospace)
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
